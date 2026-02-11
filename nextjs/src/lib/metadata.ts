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

export const extractLocaleFromFilename = (filename: string): string | undefined => {
  if (!filename) return undefined;
  // Match patterns like zh_CN, en-US, fr-sn, etc.
  const regex = /\b([a-z]{2}[-_][A-Z]{2})\b|\b([a-z]{2}[-_][a-z]{2})\b/g;
  const match = filename.match(regex);
  return match ? match[0] : undefined;
};

export const extractLocaleAndPageId = (
  payload: unknown,
  filename?: string,
): { locale?: string; pageId?: string } => {
  let locale = filename ? extractLocaleFromFilename(filename) : undefined;
  let pageId: string | undefined;

  if (!payload || typeof payload !== "object") return { locale, pageId };

  const visited = new Set<object>();
  const queue: unknown[] = [payload];
  const MAX_NODES = 1500;

  while (queue.length && visited.size < MAX_NODES && (!locale || !pageId)) {
    const current = queue.shift();
    if (!current || typeof current !== "object") continue;
    if (visited.has(current as object)) continue;
    visited.add(current as object);

    if (Array.isArray(current)) {
      for (const entry of current) {
        if (entry && typeof entry === "object") {
          queue.push(entry);
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
        queue.push(value);
      }
    }
  }

  return { locale, pageId };
};