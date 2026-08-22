import { onCall, HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import * as admin from "firebase-admin";

interface ListMemberData {
  uid: string;
  displayName?: string;
  email?: string;
  photoUrl?: string;
}

async function unassignTasks(listRef: admin.firestore.DocumentReference, uid: string) {
  const tasks = await listRef.collection("tasks").where("assigneeId", "==", uid).get();
  if (tasks.empty) return;
  const batch = admin.firestore().batch();
  for (const taskDoc of tasks.docs) {
    batch.update(taskDoc.ref, { assigneeId: "", assigneeName: "" });
  }
  await batch.commit();
}

/**
 * Deletes the caller's own account: on every list they're part of, either
 * hands off ownership or removes their membership, unassigns any tasks
 * still assigned to them, deletes their `users/{uid}` profile, and
 * finally deletes their Firebase Auth account. Runs as a callable rather
 * than client-side both because one user must never be able to delete
 * another's account, and because firestore.rules blocks client deletes of
 * `users/{uid}` outright - only trusted server code (Admin SDK) can do
 * this cascade.
 */
export const deleteAccount = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "You must be signed in to delete your account.");
  }

  const firestore = admin.firestore();

  const [ownedLists, memberLists] = await Promise.all([
    firestore.collection("lists").where("ownerId", "==", uid).get(),
    firestore.collection("lists").where("memberIds", "array-contains", uid).get(),
  ]);

  for (const listDoc of ownedLists.docs) {
    const list = listDoc.data();
    const memberIds: string[] = list.memberIds ?? [];
    const members: ListMemberData[] = list.members ?? [];
    const remainingMemberIds = memberIds.filter((id) => id !== uid);

    if (list.visibility === "SHARED" && remainingMemberIds.length > 0) {
      // Someone else still needs this list, so hand ownership to the
      // longest-standing remaining member instead of deleting it out from
      // under them.
      const newOwnerId = remainingMemberIds[0];
      const newOwner = members.find((m) => m.uid === newOwnerId);
      await listDoc.ref.update({
        ownerId: newOwnerId,
        ownerName: newOwner?.displayName ?? "",
        memberIds: remainingMemberIds,
        members: members.filter((m) => m.uid !== uid),
      });
      await unassignTasks(listDoc.ref, uid);
    } else {
      // Private, or shared with no one left to hand it to - nothing of
      // this list needs to survive the account it belongs to.
      await firestore.recursiveDelete(listDoc.ref);
    }
  }

  for (const listDoc of memberLists.docs) {
    if (listDoc.data().ownerId === uid) continue; // handled above
    const members: ListMemberData[] = listDoc.data().members ?? [];
    await listDoc.ref.update({
      memberIds: admin.firestore.FieldValue.arrayRemove(uid),
      members: members.filter((m) => m.uid !== uid),
    });
    await unassignTasks(listDoc.ref, uid);
  }

  await firestore.collection("users").doc(uid).delete();

  try {
    await admin.auth().deleteUser(uid);
  } catch (error) {
    logger.error(`Deleted data for ${uid} but failed to delete their Auth account`, error);
    throw new HttpsError(
      "internal",
      "Your data was deleted, but removing your sign-in account failed. Please contact support.",
    );
  }

  logger.info(`Deleted account and data for ${uid}`);
  return { success: true };
});
