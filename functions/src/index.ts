import * as admin from "firebase-admin";

admin.initializeApp();

export { onTaskWrite, dueDateReminders } from "./notifications";
export { deleteAccount } from "./accountDeletion";
