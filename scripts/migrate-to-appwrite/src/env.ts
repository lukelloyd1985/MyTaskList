/** Reads and validates all configuration from process.env. No secrets are
 *  ever hardcoded here or written to disk - see .env.example for the
 *  documented variable names (not real values). */

export interface Config {
  // Firestore (read side)
  firestoreDatabaseId: string;

  // Appwrite (write side)
  appwriteEndpoint: string;
  appwriteProjectId: string;
  appwriteApiKey: string;
  appwriteDatabaseId: string;
  appwriteUsersCollectionId: string;
  appwriteListsCollectionId: string;
  appwriteTasksCollectionId: string;
}

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `Missing required environment variable ${name}. See scripts/migrate-to-appwrite/.env.example.`,
    );
  }
  return value;
}

function optional(name: string, fallback: string): string {
  const value = process.env[name];
  return value && value.length > 0 ? value : fallback;
}

/** Reads config needed for the write side (Appwrite). Deferred until after
 *  --dry-run is checked so a dry run never demands write credentials -
 *  only the read side is required to compute and print the dry-run
 *  summary. */
export function loadConfig(opts: { dryRun: boolean }): Config {
  // GOOGLE_APPLICATION_CREDENTIALS is read directly by firebase-admin's
  // Application Default Credentials resolution, not by this script - we
  // still sanity-check it's set so the failure mode is an early, clear
  // error instead of an opaque firebase-admin auth error later.
  required("GOOGLE_APPLICATION_CREDENTIALS");

  const firestoreDatabaseId = optional("FIRESTORE_DATABASE_ID", "mytasks");

  if (opts.dryRun) {
    // Dry runs never write, so Appwrite credentials are optional - fall
    // back to harmless placeholders that are never actually used for a
    // network call.
    return {
      firestoreDatabaseId,
      appwriteEndpoint: optional("APPWRITE_ENDPOINT", "https://cloud.appwrite.io/v1"),
      appwriteProjectId: optional("APPWRITE_PROJECT_ID", "(dry-run: not set)"),
      appwriteApiKey: optional("APPWRITE_API_KEY", "(dry-run: not set)"),
      appwriteDatabaseId: optional("APPWRITE_DATABASE_ID", "mytasks"),
      appwriteUsersCollectionId: optional("APPWRITE_COLLECTION_USERS_ID", "users"),
      appwriteListsCollectionId: optional("APPWRITE_COLLECTION_LISTS_ID", "lists"),
      appwriteTasksCollectionId: optional("APPWRITE_COLLECTION_TASKS_ID", "tasks"),
    };
  }

  return {
    firestoreDatabaseId,
    appwriteEndpoint: optional("APPWRITE_ENDPOINT", "https://cloud.appwrite.io/v1"),
    appwriteProjectId: required("APPWRITE_PROJECT_ID"),
    appwriteApiKey: required("APPWRITE_API_KEY"),
    appwriteDatabaseId: optional("APPWRITE_DATABASE_ID", "mytasks"),
    appwriteUsersCollectionId: optional("APPWRITE_COLLECTION_USERS_ID", "users"),
    appwriteListsCollectionId: optional("APPWRITE_COLLECTION_LISTS_ID", "lists"),
    appwriteTasksCollectionId: optional("APPWRITE_COLLECTION_TASKS_ID", "tasks"),
  };
}
