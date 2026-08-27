import { Databases, Query, Models } from "node-appwrite";

/** Appwrite's listDocuments() caps out at a page (default/max 100 with
 *  Query.limit) rather than returning everything the way Firestore's
 *  `.get()` on a query does. Account deletion has to see *every* list and
 *  task the user touches or the cascade below silently leaves orphaned
 *  data behind, so this pages through with cursorAfter until a
 *  short page signals the end. */
export async function listAllDocuments<T extends Models.Document>(
  databases: Databases,
  databaseId: string,
  collectionId: string,
  queries: string[],
): Promise<T[]> {
  const pageSize = 100;
  const results: T[] = [];
  let cursor: string | undefined;

  for (;;) {
    const pageQueries = [...queries, Query.limit(pageSize)];
    if (cursor) pageQueries.push(Query.cursorAfter(cursor));

    const page = await databases.listDocuments<T>(databaseId, collectionId, pageQueries);
    results.push(...page.documents);

    if (page.documents.length < pageSize) break;
    cursor = page.documents[page.documents.length - 1].$id;
  }

  return results;
}
