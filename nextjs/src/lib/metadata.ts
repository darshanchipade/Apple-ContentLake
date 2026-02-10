import { pickString } from "./source";

const LOCALE_KEYS = ["locale", "localeCode", "locale_code", "languageLocale", "language_locale"];
const PAGE_ID_KEYS = ["pageId", "page_id", "pageID"];

const pickFromRecord = (
  record: Record<string, unknown> | null | undefined,
  keys: string[],
): string | undefined => {
  if (!record) return undefined;
  for (const key of keys) {
    const value = pickString(record[key]);
    if (value) return value;
  }
  return undefined;
};

export const pickLocale = (
  record: Record<string, unknown> | null | undefined,
): string | undefined => pickFromRecord(record, LOCALE_KEYS);

export const pickPageId = (
  record: Record<string, unknown> | null | undefined,
): string | undefined => pickFromRecord(record, PAGE_ID_KEYS);

export const extractLocaleAndPageId = (
  payload: unknown,
): { locale?: string; pageId?: string } => {
  if (!payload || typeof payload !== "object") return {};
  const visited = new Set<object>();
  const stack: unknown[] = [payload];
  const MAX_NODES = 1500;
  let locale: string | undefined;
  let pageId: string | undefined;

  while (stack.length && visited.size < MAX_NODES && (!locale || !pageId)) {
    const current = stack.pop();
    if (!current || typeof current !== "object") continue;
    if (visited.has(current as object)) continue;
    visited.add(current as object);

    if (Array.isArray(current)) {
      for (let index = current.length - 1; index >= 0; index -= 1) {
        const entry = current[index];
        if (entry && typeof entry === "object") {
          stack.push(entry);
        }
      }
      continue;
    }

    const record = current as Record<string, unknown>;
    if (!locale) {
      locale = pickLocale(record);
    }
    if (!pageId) {
      pageId = pickPageId(record);
    }

    if (locale && pageId) {
      break;
    }

    for (const value of Object.values(record)) {
      if (value && typeof value === "object") {
        stack.push(value);
      }
    }
  }

  return { locale, pageId };
};