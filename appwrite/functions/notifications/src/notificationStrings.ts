export type SupportedLocale = "en" | "sk" | "cs" | "fr" | "de" | "es" | "it" | "ru";

/** Mirrors the values(-sk/-cs/-fr/-de/-es/-it/-ru)/strings.xml translations
 *  on the Android side (see README "Localization") for the two
 *  notification kinds this backend sends - kept here rather than
 *  templated from the client since these are sent from a server-side
 *  function with no UI context. `untitled` is what a task's body falls
 *  back to when it has no title.
 *
 *  Ported verbatim from the original Firebase function
 *  (functions/src/notifications.ts). */
export const NOTIFICATION_STRINGS: Record<SupportedLocale, {
  assigned: { title: string; untitled: string };
  dueSoon: { title: string; untitled: string };
}> = {
  en: {
    assigned: { title: "You were assigned a task", untitled: "New task" },
    dueSoon: { title: "Task due soon", untitled: "Task" },
  },
  sk: {
    assigned: { title: "Bola vám priradená úloha", untitled: "Nová úloha" },
    dueSoon: { title: "Termín úlohy sa blíži", untitled: "Úloha" },
  },
  cs: {
    assigned: { title: "Byl vám přiřazen úkol", untitled: "Nový úkol" },
    dueSoon: { title: "Termín úkolu se blíží", untitled: "Úkol" },
  },
  fr: {
    assigned: { title: "Une tâche vous a été attribuée", untitled: "Nouvelle tâche" },
    dueSoon: { title: "Échéance de tâche proche", untitled: "Tâche" },
  },
  de: {
    assigned: { title: "Ihnen wurde eine Aufgabe zugewiesen", untitled: "Neue Aufgabe" },
    dueSoon: { title: "Aufgabe bald fällig", untitled: "Aufgabe" },
  },
  es: {
    assigned: { title: "Se te asignó una tarea", untitled: "Nueva tarea" },
    dueSoon: { title: "Tarea próxima a vencer", untitled: "Tarea" },
  },
  it: {
    assigned: { title: "Ti è stata assegnata un'attività", untitled: "Nuova attività" },
    dueSoon: { title: "Attività in scadenza", untitled: "Attività" },
  },
  ru: {
    assigned: { title: "Вам назначена задача", untitled: "Новая задача" },
    dueSoon: { title: "Срок задачи скоро истекает", untitled: "Задача" },
  },
};

const SUPPORTED_LOCALES = new Set<SupportedLocale>(["en", "sk", "cs", "fr", "de", "es", "it", "ru"]);

export function resolveLocale(locale: unknown): SupportedLocale {
  return typeof locale === "string" && SUPPORTED_LOCALES.has(locale as SupportedLocale)
    ? (locale as SupportedLocale)
    : "en";
}
