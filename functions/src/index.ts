import { initializeApp } from "firebase-admin/app";

initializeApp();

export { onTaskWrite, dueDateReminders } from "./notifications";
export { deleteAccount } from "./accountDeletion";
