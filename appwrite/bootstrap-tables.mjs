#!/usr/bin/env node
// Bootstraps the database and tables declared in appwrite.json directly
// via the node-appwrite server SDK, bypassing `appwrite push tables`
// entirely.
//
// WHY THIS EXISTS: `appwrite push tables all --force`, run against the
// `mytasks` database while it has zero existing tables, has repeatedly
// planned to DELETE the database outright before creating anything -
// even with appwrite.json's schema fully correct (confirmed by a real
// run with --force removed: it still planned the exact same deletion,
// just refused to proceed without an interactive confirmation instead of
// silently deleting). This looks like inherent behavior of `push
// tables`'s diffing against an empty database, not a fixable config bug
// - see deploy-appwrite.yml's top-of-file comment for the full history.
// This script sidesteps `push tables` for the one-time bootstrap
// entirely, creating the database/tables/columns/indexes directly via
// the API instead.
//
// Purely additive - it never deletes or modifies anything, and treats
// "already exists" (HTTP 409) as success - so it's safe to re-run. Once
// the database has tables in it, `appwrite push tables` (still used for
// ongoing schema changes - see deploy-appwrite.yml) hasn't shown this
// deletion behavior against a non-empty database.
//
// Requires APPWRITE_ENDPOINT and APPWRITE_API_KEY env vars (the same
// ones deploy-appwrite.yml already uses for `appwrite client`). The
// project ID and every resource definition come from appwrite.json
// itself - nothing is duplicated here.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { AppwriteException, Client, TablesDB } from "node-appwrite";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const config = JSON.parse(
  readFileSync(path.join(__dirname, "appwrite.json"), "utf8"),
);

const endpoint = process.env.APPWRITE_ENDPOINT;
const apiKey = process.env.APPWRITE_API_KEY;
if (!endpoint || !apiKey) {
  console.error(
    "APPWRITE_ENDPOINT and APPWRITE_API_KEY must both be set.",
  );
  process.exit(1);
}

const client = new Client()
  .setEndpoint(endpoint)
  .setProject(config.projectId)
  .setKey(apiKey);

const tablesDB = new TablesDB(client);

function isAlreadyExists(err) {
  return err instanceof AppwriteException && err.code === 409;
}

async function ensureDatabase(db) {
  try {
    await tablesDB.create({
      databaseId: db.$id,
      name: db.name,
      enabled: db.enabled ?? true,
    });
    console.log(`Created database "${db.$id}"`);
  } catch (err) {
    if (isAlreadyExists(err)) {
      console.log(`Database "${db.$id}" already exists, skipping`);
    } else {
      throw err;
    }
  }
}

async function ensureTable(table) {
  try {
    await tablesDB.createTable({
      databaseId: table.databaseId,
      tableId: table.$id,
      name: table.name,
      permissions: table.$permissions,
      rowSecurity: table.rowSecurity,
      enabled: table.enabled ?? true,
      columns: table.columns,
      indexes: table.indexes,
    });
    console.log(`Created table "${table.$id}" (with its columns and indexes)`);
  } catch (err) {
    if (isAlreadyExists(err)) {
      console.log(
        `Table "${table.$id}" already exists, skipping - this script ` +
          `never updates existing tables, use "appwrite push tables" for ` +
          `incremental schema changes once bootstrap is done`,
      );
    } else {
      throw err;
    }
  }
}

for (const db of config.databases ?? []) {
  await ensureDatabase(db);
}
for (const table of config.tables ?? []) {
  await ensureTable(table);
}

console.log("Bootstrap complete.");
