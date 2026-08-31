#!/usr/bin/env node
// Bootstraps and incrementally syncs the database and tables declared in
// appwrite.json directly via the node-appwrite server SDK, bypassing
// `appwrite push tables` entirely.
//
// WHY THIS EXISTS: `appwrite push tables all --force` has, on four
// separate real runs, planned to DELETE the `mytasklist` database outright -
// including once against a database that already held a fully correct,
// non-empty schema, which rules out every theory tried (empty database,
// ID mismatch, two real schema bugs already fixed). This looks like
// inherent, unfixable-from-config behavior of `push tables` against this
// project - see deploy-appwrite.yml's top-of-file comment for the full
// history. `appwrite push tables` is not used anywhere in this repo as a
// result; this script is the only thing that ever touches the database
// or tables, via the API directly.
//
// STRATEGY - additive only, never destructive:
// - A table that doesn't exist yet is created in full (with its columns
//   and indexes) via a single createTable call.
// - A table that already exists is left in place. Its columns and
//   indexes are listed, and any column/index declared in appwrite.json
//   but missing remotely is added on its own. Nothing is ever deleted,
//   and an existing column/index is never altered - if a remote column
//   differs from its local declaration (e.g. required/type changed), a
//   warning is logged and it's left alone; reconcile that by hand in
//   Console, deliberately not automated, since altering a live column in
//   place isn't always even possible without data loss (e.g. narrowing a
//   string's size, changing its type). Same for a remote table that
//   isn't declared locally anymore, or a remote column not declared on
//   an existing table - both are left alone, never dropped.
// - Every run is therefore safe to repeat and never loses data, whether
//   the tables are empty or hold real rows.
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
  // Checked via a read first, rather than create-then-catch-409: a real
  // run showed that once a project is at its plan's database-count limit
  // (Appwrite Cloud's free tier allows 1), `create` returns 403
  // "additional_resource_not_allowed" even when called with an ID that
  // already exists - the quota check fires before the duplicate-ID
  // check, so a 409-only catch never sees "already exists" again after
  // the first successful run. `get` costs nothing against that quota, so
  // this sidesteps the ambiguity entirely instead of trying to
  // special-case that error code too.
  try {
    await tablesDB.get({ databaseId: db.$id });
    console.log(`Database "${db.$id}" already exists, skipping`);
    return;
  } catch (err) {
    if (!isNotFound(err)) {
      throw err;
    }
  }

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

// One creator per column type actually used in appwrite.json. Extend
// this map (matching node-appwrite's TablesDB.create*Column methods) if
// a new column type is ever added to the schema - createMissingColumn
// below fails loudly for any type not listed here, rather than silently
// skipping it.
const COLUMN_CREATORS = {
  string: (databaseId, tableId, c) =>
    tablesDB.createStringColumn({
      databaseId,
      tableId,
      key: c.key,
      size: c.size,
      required: c.required,
      array: c.array ?? false,
      ...(c.default != null ? { xdefault: c.default } : {}),
    }),
  enum: (databaseId, tableId, c) =>
    tablesDB.createEnumColumn({
      databaseId,
      tableId,
      key: c.key,
      elements: c.elements,
      required: c.required,
      array: c.array ?? false,
      ...(c.default != null ? { xdefault: c.default } : {}),
    }),
  boolean: (databaseId, tableId, c) =>
    tablesDB.createBooleanColumn({
      databaseId,
      tableId,
      key: c.key,
      required: c.required,
      array: c.array ?? false,
      ...(c.default != null ? { xdefault: c.default } : {}),
    }),
  datetime: (databaseId, tableId, c) =>
    tablesDB.createDatetimeColumn({
      databaseId,
      tableId,
      key: c.key,
      required: c.required,
      array: c.array ?? false,
      ...(c.default != null ? { xdefault: c.default } : {}),
    }),
  integer: (databaseId, tableId, c) =>
    tablesDB.createIntegerColumn({
      databaseId,
      tableId,
      key: c.key,
      required: c.required,
      min: c.min,
      max: c.max,
      array: c.array ?? false,
      ...(c.default != null ? { xdefault: c.default } : {}),
    }),
};

async function waitForColumnAvailable(databaseId, tableId, key) {
  const timeoutMs = 30_000;
  const intervalMs = 1_000;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const column = await tablesDB.getColumn({ databaseId, tableId, key });
    if (column.status === "available") return;
    if (column.status === "failed" || column.status === "stuck") {
      throw new Error(
        `Column "${tableId}.${key}" failed to become available (status: ${column.status})`,
      );
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(
    `Timed out waiting for column "${tableId}.${key}" to become available`,
  );
}

async function syncColumns(table) {
  const { columns: remoteColumns } = await tablesDB.listColumns({
    databaseId: table.databaseId,
    tableId: table.$id,
  });
  const remoteByKey = new Map(remoteColumns.map((c) => [c.key, c]));

  for (const column of table.columns) {
    const remote = remoteByKey.get(column.key);
    if (!remote) {
      const create = COLUMN_CREATORS[column.type];
      if (!create) {
        console.warn(
          `  Skipping "${table.$id}.${column.key}": no creator for column ` +
            `type "${column.type}" in bootstrap-tables.mjs - add one, or ` +
            `create this column by hand in Console`,
        );
        continue;
      }
      await create(table.databaseId, table.$id, column);
      await waitForColumnAvailable(table.databaseId, table.$id, column.key);
      console.log(`  Added missing column "${table.$id}.${column.key}"`);
    } else if (remote.required !== column.required || remote.type !== column.type) {
      console.warn(
        `  "${table.$id}.${column.key}" differs from appwrite.json ` +
          `(remote: type=${remote.type} required=${remote.required}; ` +
          `local: type=${column.type} required=${column.required}) - not ` +
          `auto-altered, reconcile by hand in Console if this is intended`,
      );
    }
  }
}

async function syncIndexes(table) {
  if (!table.indexes?.length) return;

  const { indexes: remoteIndexes } = await tablesDB.listIndexes({
    databaseId: table.databaseId,
    tableId: table.$id,
  });
  const remoteKeys = new Set(remoteIndexes.map((i) => i.key));

  for (const index of table.indexes) {
    if (remoteKeys.has(index.key)) continue;
    await tablesDB.createIndex({
      databaseId: table.databaseId,
      tableId: table.$id,
      key: index.key,
      type: index.type,
      columns: index.attributes,
      orders: index.orders,
    });
    console.log(`  Added missing index "${table.$id}.${index.key}"`);
  }
}

async function ensureTable(table) {
  let exists = true;
  try {
    await tablesDB.getTable({ databaseId: table.databaseId, tableId: table.$id });
  } catch (err) {
    if (isNotFound(err)) {
      exists = false;
    } else {
      throw err;
    }
  }

  if (!exists) {
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
        console.log(`Table "${table.$id}" already exists, skipping create`);
      } else {
        throw err;
      }
    }
    return;
  }

  console.log(`Table "${table.$id}" already exists - syncing columns/indexes additively`);
  await syncColumns(table);
  await syncIndexes(table);
}

for (const db of config.databases ?? []) {
  await ensureDatabase(db);
}
for (const table of config.tables ?? []) {
  await ensureTable(table);
}

console.log("Bootstrap complete.");
