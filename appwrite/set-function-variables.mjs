#!/usr/bin/env node
// Sets/updates the `maintenance` Function's GOOGLE_WEB_CLIENT_ID
// environment variable via the node-appwrite server SDK.
//
// WHY THIS EXISTS: `appwrite push function` (see deploy-appwrite.yml)
// only syncs a Function's code and appwrite.json-declared config
// (entrypoint, scopes, events, schedule, etc.) - it does not manage
// Function environment variables, which live entirely in Console (or via
// this API) and aren't part of appwrite.json at all. Without this,
// GOOGLE_WEB_CLIENT_ID has to be set by hand in Console after every
// fresh project setup, which is exactly what the "GOOGLE_WEB_CLIENT_ID
// environment variable is not set" 500 from googleSignIn.ts means. This
// closes that gap the same way bootstrap-tables.mjs closes the
// equivalent gap for tables `appwrite push` can't be trusted with.
//
// Idempotent: safe to run on every deploy. Only creates/updates the one
// variable this repo actually declares needing (see main.ts's dispatch
// and googleSignIn.ts) - never touches any other variable that might
// already be set on the function by hand.
//
// Requires APPWRITE_ENDPOINT, APPWRITE_API_KEY (the same ones
// bootstrap-tables.mjs and deploy-appwrite.yml already use) and
// GOOGLE_WEB_CLIENT_ID (the same Google Cloud OAuth 2.0 Web application
// Client ID value as the Android app's GOOGLE_WEB_CLIENT_ID
// build env var - see README "Backend setup" step 6). The project ID
// comes from appwrite.json, matching bootstrap-tables.mjs - nothing is
// duplicated here.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { Client, Functions, ID } from "node-appwrite";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const config = JSON.parse(
  readFileSync(path.join(__dirname, "appwrite.json"), "utf8"),
);

const endpoint = process.env.APPWRITE_ENDPOINT;
const apiKey = process.env.APPWRITE_API_KEY;
const googleWebClientId = process.env.GOOGLE_WEB_CLIENT_ID;
if (!endpoint || !apiKey || !googleWebClientId) {
  console.error(
    "APPWRITE_ENDPOINT, APPWRITE_API_KEY, and GOOGLE_WEB_CLIENT_ID must all be set.",
  );
  process.exit(1);
}

const client = new Client()
  .setEndpoint(endpoint)
  .setProject(config.projectId)
  .setKey(apiKey);

const functions = new Functions(client);

const FUNCTION_ID = "maintenance";
const VARIABLE_KEY = "GOOGLE_WEB_CLIENT_ID";

const { variables } = await functions.listVariables({ functionId: FUNCTION_ID });
const existing = variables.find((v) => v.key === VARIABLE_KEY);

if (!existing) {
  await functions.createVariable({
    functionId: FUNCTION_ID,
    variableId: ID.unique(),
    key: VARIABLE_KEY,
    value: googleWebClientId,
  });
  console.log(`Created "${FUNCTION_ID}" Function variable "${VARIABLE_KEY}"`);
} else if (existing.value !== googleWebClientId) {
  await functions.updateVariable({
    functionId: FUNCTION_ID,
    variableId: existing.$id,
    key: VARIABLE_KEY,
    value: googleWebClientId,
  });
  console.log(`Updated "${FUNCTION_ID}" Function variable "${VARIABLE_KEY}"`);
} else {
  console.log(`"${FUNCTION_ID}" Function variable "${VARIABLE_KEY}" already up to date, skipping`);
}
