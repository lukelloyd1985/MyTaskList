import { TablesDB, Query, Models } from "node-appwrite";

/** Appwrite's listRows() caps out at a page (default/max 100 with
 *  Query.limit) rather than returning everything the way Firestore's
 *  `.get()` on a query does. Account deletion has to see *every* list and
 *  task the user touches or the cascade below silently leaves orphaned
 *  data behind, so this pages through with cursorAfter until a
 *  short page signals the end. */
export async function listAllRows<T extends Models.Row>(
  tablesDB: TablesDB,
  databaseId: string,
  tableId: string,
  queries: string[],
): Promise<T[]> {
  const pageSize = 100;
  const results: T[] = [];
  let cursor: string | undefined;

  for (;;) {
    const pageQueries = [...queries, Query.limit(pageSize)];
    if (cursor) pageQueries.push(Query.cursorAfter(cursor));

    const page = await tablesDB.listRows<T>({ databaseId, tableId, queries: pageQueries });
    results.push(...page.rows);

    if (page.rows.length < pageSize) break;
    cursor = page.rows[page.rows.length - 1].$id;
  }

  return results;
}
