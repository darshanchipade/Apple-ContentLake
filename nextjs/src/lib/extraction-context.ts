import { TreeNode } from "./tree";

export type ExtractionContext = {
  mode: "local" | "api" | "s3";
  metadata: {
    name: string;
    size: number;
    source: string;
    cleansedId?: string;
    status?: string;
    uploadedAt: number;
    sourceIdentifier?: string;
    sourceType?: string;
    locale?: string;
    pageId?: string;
  };
  sourceUri?: string;
  snapshotId?: string;
  /**
   * @deprecated Legacy fields retained for backwards compatibility.
   */
  tree?: TreeNode[];
  rawJson?: string;
  backendPayload?: unknown;
};

export type PersistenceFailureReason = "ssr" | "quota" | "unknown";

export type PersistenceResult = {
  ok: boolean;
  reason?: PersistenceFailureReason;
  usedFallback?: boolean;
};

const STORAGE_KEY = "extraction-context";
const CLEANSED_STORAGE_KEY = "cleansed-context";
const ENRICHMENT_STORAGE_KEY = "enrichment-context";

const MAX_CLEANSED_ITEMS = 150;
const MAX_CLEANSED_RAW_BODY_CHARS = 200_000;

const isQuotaExceededError = (error: unknown): error is DOMException => {
  if (typeof DOMException === "undefined") return false;

  return (
    error instanceof DOMException &&
    (error.name === "QuotaExceededError" ||
      error.name === "NS_ERROR_DOM_QUOTA_REACHED" ||
      error.code === 22 ||
      error.code === 1014)
  );
};

const MAX_EXTRACTION_RAW_BODY_CHARS = 500_000;

const persistToSessionStorage = (key: string, payload: unknown): PersistenceResult => {
  if (typeof window === "undefined") {
    return { ok: false, reason: "ssr" };
  }

  try {
    sessionStorage.setItem(key, JSON.stringify(payload));
    return { ok: true };
  } catch (error) {
    if (isQuotaExceededError(error)) {
      console.warn(`Session storage quota exceeded while saving '${key}'.`);
      return { ok: false, reason: "quota" };
    }

    console.error(`Failed to persist '${key}' to sessionStorage`, error);
    return { ok: false, reason: "unknown" };
  }
};

const safeLoad = <T>(serialized: string | null): T | null => {
  if (!serialized) return null;

  try {
    return JSON.parse(serialized) as T;
  } catch (error) {
    console.error("Failed to parse session storage payload", error);
    return null;
  }
};

const sanitizeExtractionContext = (payload: ExtractionContext): ExtractionContext => {
  const next: ExtractionContext = {
    ...payload,
    backendPayload: undefined,
  };

  if (typeof next.rawJson === "string" && next.rawJson.length > MAX_EXTRACTION_RAW_BODY_CHARS) {
    next.rawJson = next.rawJson.slice(0, MAX_EXTRACTION_RAW_BODY_CHARS);
  }

  return next;
};

export const saveExtractionContext = (payload: ExtractionContext) => {
  const sanitized = sanitizeExtractionContext(payload);
  return persistToSessionStorage(STORAGE_KEY, sanitized);
};

export const loadExtractionContext = (): ExtractionContext | null => {
  if (typeof window === "undefined") return null;
  const stored = sessionStorage.getItem(STORAGE_KEY);
  return safeLoad<ExtractionContext>(stored);
};

export const clearExtractionContext = () => {
  if (typeof window === "undefined") return;
  sessionStorage.removeItem(STORAGE_KEY);
};

export type CleansedContext = {
  metadata: ExtractionContext["metadata"];
  items: unknown[];
  rawBody?: string;
  status?: string;
  itemsTruncated?: boolean;
  rawBodyTruncated?: boolean;
  fallbackReason?: PersistenceFailureReason;
};

const sanitizeCleansedPayload = (payload: CleansedContext): CleansedContext => {
  const next: CleansedContext = {
    ...payload,
    items: Array.isArray(payload.items) ? [...payload.items] : [],
  };

  if (next.items.length > MAX_CLEANSED_ITEMS) {
    next.items = next.items.slice(0, MAX_CLEANSED_ITEMS);
    next.itemsTruncated = true;
  }

  if (typeof next.rawBody === "string" && next.rawBody.length > MAX_CLEANSED_RAW_BODY_CHARS) {
    next.rawBody = next.rawBody.slice(0, MAX_CLEANSED_RAW_BODY_CHARS);
    next.rawBodyTruncated = true;
  }

  return next;
};

export const saveCleansedContext = (payload: CleansedContext): PersistenceResult => {
  const sanitized = sanitizeCleansedPayload(payload);
  const result = persistToSessionStorage(CLEANSED_STORAGE_KEY, sanitized);

  if (!result.ok && result.reason === "quota") {
    const fallbackPayload: CleansedContext = {
      metadata: sanitized.metadata,
      status: sanitized.status,
      items: [],
      fallbackReason: "quota",
    };
    const fallbackResult = persistToSessionStorage(CLEANSED_STORAGE_KEY, fallbackPayload);
    if (fallbackResult.ok) {
      return {
        ok: true,
        reason: "quota",
        usedFallback: true,
      };
    }
    return fallbackResult;
  }

  return result;
};

export const loadCleansedContext = (): CleansedContext | null => {
  if (typeof window === "undefined") return null;
  const stored = sessionStorage.getItem(CLEANSED_STORAGE_KEY);
  return safeLoad<CleansedContext>(stored);
};

export const clearCleansedContext = () => {
  if (typeof window === "undefined") return;
  sessionStorage.removeItem(CLEANSED_STORAGE_KEY);
};

export type EnrichmentStatusEntry = {
  status: string;
  timestamp: number;
};

export type EnrichmentContext = {
  metadata: ExtractionContext["metadata"];
  items?: unknown[];
  startedAt: number;
  statusHistory: EnrichmentStatusEntry[];
};

export const saveEnrichmentContext = (payload: EnrichmentContext) => {
  return persistToSessionStorage(ENRICHMENT_STORAGE_KEY, payload);
};

export const loadEnrichmentContext = (): EnrichmentContext | null => {
  if (typeof window === "undefined") return null;
  const stored = sessionStorage.getItem(ENRICHMENT_STORAGE_KEY);
  return safeLoad<EnrichmentContext>(stored);
};

export const clearEnrichmentContext = () => {
  if (typeof window === "undefined") return;
  sessionStorage.removeItem(ENRICHMENT_STORAGE_KEY);
};