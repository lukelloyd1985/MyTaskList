#!/usr/bin/env node
// Bootstraps the database and tables declared in appwrite.json directly
// via the node-appwrite server SDK, bypassing `appwrite push tables`
// entirely.
//
// WHY THIS EXISTS: `appwrite push tables all --force` has, on four
// separate real runs, planned to DELETE the `mytasks` database outright -
// including once against a database that already held a fully correct,
// non-empty schema, which rules out every theory tried (empty database,
// ID mismatch, two real schema bugs already fixed). This looks like
// inherent, unfixable-from-config behavior of `push tables` against this
// project - see deploy-appwrite.yml's top-of-file comment for the full
// history. `appwrite push tables` is not used anywhere in this repo as a
// result; this script is the only thing that ever touches the database
// or tables, via the API directly.
//
// CURRENT STRATEGY - full recreate, not incremental update: every table
// declared in appwrite.json is deleted (if it exists) and recreated fresh
// on every run, so the deployed schema always exactly matches
// appwrite.json. This is only safe because the project has no real user
// data in Appwrite yet - deleting a table deletes every row in it.
// BEFORE THIS PROJECT HAS REAL DATA IN APPWRITE, THIS MUST CHANGE to a
// non-destructive incremental-update strategy (e.g. diffing columns/
// indexes and only adding what's missing, never dropping a table that
// already has rows) - don't copy this delete-and-recreate approach into a
// project with real data to protect.
//
// The database itself is never deleted or recreated - only tables.
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

function isNotFound(err) {
  return err instanceof AppwriteException && err.code === 404;
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

async function recreateTable(table) {
  try {
    await tablesDB.deleteTable({
      databaseId: table.databaseId,
      tableId: table.$id,
    });
    console.log(`Deleted existing table "${table.$id}"`);
  } catch (err) {
    if (!isNotFound(err)) {
      throw err;
    }
  }

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
}

for (const db of config.databases ?? []) {
  await ensureDatabase(db);
}
for (const table of config.tables ?? []) {
  await recreateTable(table);
}

console.log("Bootstrap complete.");
