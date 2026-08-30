#!/usr/bin/env node
// Sets each Function's deploymentRetention and prunes its already-stale
// deployments, via the node-appwrite server SDK directly.
//
// WHY THIS EXISTS: every `appwrite push function` run (this workflow is
// manually triggered, but has run many times over the course of live
// debugging) uploads a brand-new deployment and activates it - Appwrite
// never deletes the previous ones on its own unless a function's
// `deploymentRetention` is set. Left at its default of 0 (confirmed from
// node-appwrite's own Functions.update() type definition:
// "Days to keep non-active deployments before deletion. Value 0 means
// all deployments will be kept."), every push leaves one more inactive
// deployment sitting in Console forever - this is what a real project
// showed after enough manual redeploys.
//
// `appwrite push function` doesn't manage deploymentRetention (not part
// of its documented appwrite.json config sync, unlike
// scopes/events/schedule/etc.), so it's set here directly via
// Functions.update() instead - the same reasoning bootstrap-tables.mjs
// already applies to schema `appwrite push tables` can't be trusted
// with, and set-function-variables.mjs applies to environment variables
// `appwrite push function` doesn't touch either.
//
// Idempotent - safe to run on every deploy:
//   1. Sets deploymentRetention (see RETENTION_DAYS below) on every
//      function declared in appwrite.json, so Appwrite's own retention
//      sweep keeps pruning automatically going forward. Functions.update()
//      is a full replace, not a partial patch - confirmed by a real run
//      that wiped scopes/schedule/etc. back to empty because an earlier
//      version of this script only passed name+deploymentRetention - so
//      every other field is read back from functions.get() and restated
//      verbatim in the update() call below, or it would get clobbered.
//   2. Also explicitly deletes any already-existing non-active
//      deployment older than that window right now, rather than waiting
//      on Appwrite's own sweep to work through the backlog that piled up
//      while retention was unset. Never touches a function's current
//      *active* deployment (functions.get().deploymentId) - only ever
//      deletes non-active, expired ones.
//
// Requires APPWRITE_ENDPOINT, APPWRITE_API_KEY (same as
// bootstrap-tables.mjs/set-function-variables.mjs). The project ID and
// function IDs come from appwrite.json, nothing duplicated here.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { Client, Functions, Query } from "node-appwrite";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const config = JSON.parse(
  readFileSync(path.join(__dirname, "appwrite.json"), "utf8"),
);

const endpoint = process.env.APPWRITE_ENDPOINT;
const apiKey = process.env.APPWRITE_API_KEY;
if (!endpoint || !apiKey) {
  console.error("APPWRITE_ENDPOINT and APPWRITE_API_KEY must both be set.");
  process.exit(1);
}

// How many days of non-active deployment history to keep around (e.g. to
// roll back to a recent build). Adjust freely - this is the only knob a
// future change to the retention window needs to touch.
const RETENTION_DAYS = 1;

const client = new Client()
  .setEndpoint(endpoint)
  .setProject(config.projectId)
  .setKey(apiKey);

const functions = new Functions(client);

/** Mirrors listAllRows (appwrite/functions/maintenance/src/listAll.ts):
 *  listDeployments() pages, so this walks every page via cursorAfter
 *  before anything is deleted - deleting mid-pagination risks a
 *  cursorAfter call referencing an already-deleted deployment. */
async function listAllDeployments(functionId) {
  const pageSize = 100;
  const results = [];
  let cursor;

  for (;;) {
    const queries = [Query.limit(pageSize)];
    if (cursor) queries.push(Query.cursorAfter(cursor));

    const page = await functions.listDeployments({ functionId, queries });
    results.push(...page.deployments);

    if (page.deployments.length < pageSize) break;
    cursor = page.deployments[page.deployments.length - 1].$id;
  }

  return results;
}

const cutoff = Date.now() - RETENTION_DAYS * 24 * 60 * 60 * 1000;

for (const fn of config.functions) {
  const current = await functions.get(fn.$id);

  if (current.deploymentRetention !== RETENTION_DAYS) {
    // update() is a full replace, not a partial patch - a real deploy
    // run confirmed this the hard way: calling it with only
    // functionId/name/deploymentRetention wiped every other field
    // `appwrite push function` had just set moments earlier in the same
    // run, including scopes and schedule. So every other field has to be
    // read back from `current` and restated here verbatim - this is the
    // only way to change deploymentRetention without clobbering
    // everything else.
    await functions.update({
      functionId: fn.$id,
      name: current.name,
      runtime: current.runtime,
      execute: current.execute,
      events: current.events,
      schedule: current.schedule,
      timeout: current.timeout,
      enabled: current.enabled,
      logging: current.logging,
      entrypoint: current.entrypoint,
      commands: current.commands,
      scopes: current.scopes,
      installationId: current.installationId,
      providerRepositoryId: current.providerRepositoryId,
      providerBranch: current.providerBranch,
      providerSilentMode: current.providerSilentMode,
      providerRootDirectory: current.providerRootDirectory,
      providerBranches: current.providerBranches,
      providerPaths: current.providerPaths,
      buildSpecification: current.buildSpecification,
      runtimeSpecification: current.runtimeSpecification,
      deploymentRetention: RETENTION_DAYS,
    });
    console.log(`"${fn.$id}": set deploymentRetention to ${RETENTION_DAYS} days`);
  } else {
    console.log(`"${fn.$id}": deploymentRetention already ${RETENTION_DAYS} days, skipping`);
  }

  const deployments = await listAllDeployments(fn.$id);
  const stale = deployments.filter(
    (d) => d.$id !== current.deploymentId && new Date(d.$createdAt).getTime() < cutoff,
  );

  for (const deployment of stale) {
    await functions.deleteDeployment({ functionId: fn.$id, deploymentId: deployment.$id });
  }
  console.log(
    `"${fn.$id}": pruned ${stale.length} deployment(s) older than ${RETENTION_DAYS} days (${deployments.length} total, active deployment kept)`,
  );
}
