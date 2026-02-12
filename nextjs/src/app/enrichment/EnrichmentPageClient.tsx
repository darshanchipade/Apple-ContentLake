"use client";

import { ChevronDownIcon, ChevronRightIcon } from "@heroicons/react/24/outline";
import { useEffect, useMemo, useState } from "react";
import clsx from "clsx";
import { useRouter, useSearchParams } from "next/navigation";
import {
  clearEnrichmentContext,
  loadEnrichmentContext,
  saveEnrichmentContext,
  type EnrichmentContext,
} from "@/lib/extraction-context";
import { PipelineShell } from "@/components/PipelineShell";
import { describeSourceLabel, inferSourceType, pickString } from "@/lib/source";
import { pickLocale, pickPageId } from "@/lib/metadata";
import { formatBytes } from "../../lib/format";

type Feedback = {
  state: "idle" | "loading" | "success" | "error";
  message?: string;
};

type SummaryFeedback = {
  state: "idle" | "loading" | "error";
  message?: string;
};

type RemoteEnrichmentContext = {
  metadata: EnrichmentContext["metadata"];
  startedAt: number;
  statusHistory: { status: string; timestamp: number }[];
};

type SentimentSnapshot = {
  label: string;
  score?: number;
};

type EnrichedElement = {
  id: string;
  title: string;
  path?: string;
  copy?: string;
  summary?: string;
  status?: string | null;
  classification: string[];
  keywords: string[];
  tags: string[];
  sentiment?: SentimentSnapshot | null;
  newAiAvailable?: boolean;
  meta?: {
    fieldsTagged?: number;
    readabilityDelta?: number;
    errorsFound?: number;
  };
};

type ElementEditState = {
  summary: string;
  classification: string;
  keywords: string;
  tags: string;
  isEditingInsights: boolean;
  isEditingMetadata: boolean;
  isSavingInsights: boolean;
  isSavingMetadata: boolean;
  isGeneratingInsights: boolean;
  isGeneratingMetadata: boolean;
  error?: string;
  success?: string;
};

type EnrichmentField = "summary" | "classification" | "keywords" | "tags";

type RevisionRecord = {
  id: string;
  revision?: number;
  source?: string;
  modelUsed?: string | null;
  createdAt?: string | null;
  summary?: string | null;
  classification?: string | null;
  keywords?: string[] | null;
  tags?: string[] | null;
};

type EnrichmentOverview = {
  metrics: {
    totalFieldsTagged?: number | null;
    readabilityDelta?: number | null;
    errorsFound?: number | null;
  };
  elements: EnrichedElement[];
};

const EXCLUDED_ITEM_TYPES = [
  "analytics",
  "disclaimers",
  "pageanalyticsattributes",
  "alt",
  "analyticsattributes",
  "url",
];

const STATUS_LABELS: Record<string, string> = {
  ENRICHMENT_TRIGGERED: "Queued for enrichment",
  WAITING_FOR_RESULTS: "Awaiting AI output",
  ENRICHMENT_RUNNING: "Enrichment running",
  PARTIALLY_ENRICHED: "Partially enriched",
  ENRICHMENT_COMPLETE: "Enrichment complete",
  ENRICHED_NO_ITEMS_TO_PROCESS: "No items require enrichment",
  ERROR: "Failed",
};

const STATUS_COLORS: Record<string, { className: string; dot: string; background: string }> = {
  ENRICHMENT_TRIGGERED: {
    className: "text-primary",
    dot: "bg-primary",
    background: "bg-primary-soft",
  },
  WAITING_FOR_RESULTS: {
    className: "text-amber-700",
    dot: "bg-amber-400",
    background: "bg-amber-50",
  },
  ENRICHMENT_RUNNING: {
    className: "text-sky-700",
    dot: "bg-sky-400",
    background: "bg-sky-50",
  },
  ENRICHMENT_COMPLETE: {
    className: "text-primary",
    dot: "bg-primary",
    background: "bg-primary-soft",
  },
  ENRICHED_NO_ITEMS_TO_PROCESS: {
    className: "text-primary",
    dot: "bg-primary",
    background: "bg-primary-soft",
  },
  PARTIALLY_ENRICHED: {
    className: "text-sky-700",
    dot: "bg-sky-400",
    background: "bg-sky-50",
  },
  ERROR: {
    className: "text-rose-700",
    dot: "bg-rose-400",
    background: "bg-rose-50",
  },
};

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === "object" && value !== null;
};

const parseJsonArrayStrings = (value: string): string[] => {
  try {
    const parsed = JSON.parse(value);
    if (Array.isArray(parsed)) {
      return parsed
        .map((entry) => (typeof entry === "string" ? entry.trim() : undefined))
        .filter((entry): entry is string => Boolean(entry));
    }
  } catch {
    // ignore
  }
  return [];
};

const splitDelimitedString = (value: string): string[] => {
  const tokens = value
    .split(/[,|;>]+/)
    .map((token) => token.trim())
    .filter(Boolean);
  return tokens;
};

const serializeList = (values?: string[]): string => {
  if (!values || !values.length) return "";
  return values.join(", ");
};

const parseInputList = (value: string): string[] => {
  const normalized = value.replace(/\n+/g, ",").trim();
  if (!normalized) return [];
  const parsed = splitDelimitedString(normalized);
  return Array.from(new Set(parsed));
};

const previewText = (value?: string | null, limit = 140): string => {
  if (!value) return "";
  const trimmed = value.trim();
  if (!trimmed) return "";
  return trimmed.length > limit ? `${trimmed.slice(0, limit)}…` : trimmed;
};

const previewList = (values?: string[] | null, limit = 6): string => {
  if (!values || !values.length) return "";
  const filtered = values.filter(Boolean).slice(0, limit);
  const suffix = values.length > limit ? "…" : "";
  return `${filtered.join(", ")}${suffix}`;
};

const normalizeRevisionSource = (
  source?: string | null,
): "AI" | "USER" | "REGENERATE" | "UNKNOWN" => {
  if (!source) return "UNKNOWN";
  const normalized = source.trim().toUpperCase();
  if (normalized.includes("USER") || normalized.includes("RESTORE")) return "USER";
  if (normalized.includes("REGENERATE")) return "REGENERATE";
  if (normalized.includes("AI")) return "AI";
  return "UNKNOWN";
};

const normalizeStringList = (value: unknown): string[] => {
  if (Array.isArray(value)) {
    return value
      .map((entry) => {
        if (typeof entry === "string") return entry.trim();
        if (isRecord(entry)) {
          return (
            pickString(entry.label) ??
            pickString(entry.name) ??
            pickString(entry.value) ??
            pickString(entry.text) ??
            undefined
          );
        }
        return undefined;
      })
      .filter((entry): entry is string => Boolean(entry));
  }
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (!trimmed) return [];
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      const parsed = parseJsonArrayStrings(trimmed);
      if (parsed.length) {
        return parsed;
      }
    }
    return splitDelimitedString(trimmed);
  }
  if (isRecord(value)) {
    if (Array.isArray(value.values)) return normalizeStringList(value.values);
    if (Array.isArray(value.items)) return normalizeStringList(value.items);
    if (Array.isArray(value.labels)) return normalizeStringList(value.labels);
    if (Array.isArray(value.tags)) return normalizeStringList(value.tags);
  }
  return [];
};

const pickFromSources = (sources: Record<string, unknown>[], keys: string[]): string | undefined => {
  for (const key of keys) {
    for (const source of sources) {
      const candidate = pickString(source[key]);
      if (candidate) {
        return candidate;
      }
    }
  }
  return undefined;
};

const pickListFromSources = (sources: Record<string, unknown>[], keys: string[]): string[] => {
  for (const key of keys) {
    for (const source of sources) {
      const candidate = source[key];
      const normalized = normalizeStringList(candidate);
      if (normalized.length) {
        return normalized;
      }
    }
  }
  return [];
};

const pickBooleanFromSources = (
  sources: Record<string, unknown>[],
  keys: string[],
): boolean | undefined => {
  for (const key of keys) {
    for (const source of sources) {
      const candidate = source[key];
      if (typeof candidate === "boolean") {
        return candidate;
      }
      if (typeof candidate === "string") {
        const normalized = candidate.trim().toLowerCase();
        if (normalized === "true") return true;
        if (normalized === "false") return false;
      }
    }
  }
  return undefined;
};

const extractSentimentFromSources = (
  sources: Record<string, unknown>[],
): SentimentSnapshot | null => {
  for (const source of sources) {
    const directLabel =
      pickString(source.sentiment) ??
      pickString(source.sentimentLabel) ??
      pickString(source.sentiment_label) ??
      pickString(source.tone) ??
      pickString(source.mood);
    const scoreCandidate =
      pickNumber(source.sentimentScore) ??
      pickNumber(source.sentiment_score) ??
      pickNumber(source.score) ??
      pickNumber(source.sentimentConfidence);

    if (directLabel) {
      return { label: directLabel, score: scoreCandidate };
    }

    const nested =
      (isRecord(source.sentiment) ? (source.sentiment as Record<string, unknown>) : null) ??
      (isRecord(source.sentimentAnalysis)
        ? (source.sentimentAnalysis as Record<string, unknown>)
        : null);

    if (nested) {
      const nestedLabel =
        pickString(nested.label) ?? pickString(nested.result) ?? pickString(nested.tone);
      if (nestedLabel) {
        return {
          label: nestedLabel,
          score: pickNumber(nested.score) ?? pickNumber(nested.confidence) ?? scoreCandidate,
        };
      }
    }
  }
  return null;
};

const findFirstRecordArray = (payload: unknown, depth = 0): Record<string, unknown>[] => {
  if (depth > 5 || payload === null || payload === undefined) return [];
  if (Array.isArray(payload)) {
    const records = payload.filter(isRecord);
    if (records.length) {
      return records;
    }
    for (const entry of payload) {
      const nested = findFirstRecordArray(entry, depth + 1);
      if (nested.length) {
        return nested;
      }
    }
    return [];
  }

  if (isRecord(payload)) {
    for (const value of Object.values(payload)) {
      if (Array.isArray(value)) {
        const records = value.filter(isRecord);
        if (records.length) {
          return records;
        }
      }
    }
    for (const value of Object.values(payload)) {
      const nested = findFirstRecordArray(value, depth + 1);
      if (nested.length) {
        return nested;
      }
    }
  }

  return [];
};

const findNumberByKeys = (payload: unknown, keys: string[], depth = 0): number | undefined => {
  if (depth > 5 || payload === null || payload === undefined) return undefined;
  if (isRecord(payload)) {
    for (const key of keys) {
      if (key in payload) {
        const candidate = pickNumber(payload[key]);
        if (candidate !== undefined) {
          return candidate;
        }
      }
    }
    for (const value of Object.values(payload)) {
      const nested = findNumberByKeys(value, keys, depth + 1);
      if (nested !== undefined) {
        return nested;
      }
    }
  } else if (Array.isArray(payload)) {
    for (const value of payload) {
      const nested = findNumberByKeys(value, keys, depth + 1);
      if (nested !== undefined) {
        return nested;
      }
    }
  }
  return undefined;
};

const parseEnrichmentMetrics = (payload: unknown): EnrichmentOverview["metrics"] => {
  return {
    totalFieldsTagged: findNumberByKeys(payload, [
      "totalFieldsTagged",
      "fieldsTagged",
      "taggedFields",
      "totalFields",
      "fieldCount",
      "total_fields_tagged",
      "total_tagged_fields",
      "total_enriched_fields",
      "total_enriched_elements",
    ]),
    readabilityDelta: findNumberByKeys(payload, [
      "readabilityImproved",
      "readabilityDelta",
      "readabilityScoreDelta",
      "readability",
      "readabilityImprovement",
      "readability_improved",
      "readability_gain",
      "readability_gain_percent",
      "readabilityIncreasePercent",
    ]),
    errorsFound: findNumberByKeys(payload, [
      "errorsFound",
      "errorCount",
      "errors",
      "errors_detected",
      "error_total",
    ]),
  };
};

const pickNumberFromSources = (
  sources: Record<string, unknown>[],
  keys: string[],
): number | undefined => {
  for (const key of keys) {
    for (const source of sources) {
      const candidate = pickNumber(source[key]);
      if (candidate !== undefined) {
        return candidate;
      }
    }
  }
  return undefined;
};

const normalizeEnrichmentResult = (payload: unknown): EnrichmentOverview => {
  if (!payload) {
    return { metrics: {}, elements: [] };
  }

  const baseRecord =
    isRecord(payload) && payload.body && typeof payload.body === "object"
      ? (payload.body as Record<string, unknown>)
      : payload;

  const metrics = parseEnrichmentMetrics(baseRecord);
  let sectionRecords: Record<string, unknown>[] = [];
  if (isRecord(baseRecord)) {
    const preferredKeys = [
      "enriched_content_elements",
      "enrichedContentElements",
      "enrichment_sections",
      "enrichmentSections",
      "elements",
      "records",
      "rows",
      "data",
    ];
    for (const key of preferredKeys) {
      if (Array.isArray(baseRecord[key])) {
        sectionRecords = (baseRecord[key] as unknown[]).filter(isRecord);
        if (sectionRecords.length) break;
      }
    }
  }
  if (!sectionRecords.length) {
    sectionRecords = findFirstRecordArray(baseRecord);
  }

  if (!sectionRecords.length) {
    return { metrics, elements: [] };
  }

  const elements = sectionRecords.map((record, index) => {
    const sources: Record<string, unknown>[] = [record];
    const nestedKeys = ["data", "attributes", "meta", "context", "details", "fields"];
    nestedKeys.forEach((key) => {
      const candidate = record[key];
      if (isRecord(candidate)) {
        sources.push(candidate);
      }
    });

    const id =
      pickFromSources(sources, [
        "id",
        "sectionId",
        "elementId",
        "element_id",
        "contentId",
        "content_id",
        "hash",
        "recordId",
      ]) ??
      `element-${index}`;
    const title =
      pickFromSources(sources, [
        "item_original_field_name",
        "itemOriginalFieldName",
        "originalFieldName",
        "original_field_name",
        "title",
        "label",
        "name",
        "field",
        "section",
        "heading",
        "elementTitle",
        "element_title",
        "content_name",
      ]) ?? `Element ${index + 1}`;
    const path =
      pickFromSources(sources, [
        "item_source_path",
        "itemSourcePath",
        "path",
        "source_path",
        "breadcrumb",
        "hierarchy",
        "location",
        "elementPath",
        "element_path",
        "sectionPath",
        "section_path",
      ]) ?? pickFromSources(sources, ["parent", "group", "category"]);
    const copy =
      pickFromSources(sources, [
        "copy",
        "content",
        "text",
        "value",
        "enrichedCopy",
        "enriched_copy",
        "output",
        "body",
        "copy_text",
        "copyText",
        "copyValue",
        "aiCopy",
        "ai_copy",
        "cleansedText",
        "cleansed_text",
        "cleansedValue",
        "cleansed_value",
        "cleansedContent",
        "cleansed_content",
        "cleansedCopy",
        "cleansed_copy",
      ]) ?? undefined;
    const summaryValue =
      pickFromSources(sources, ["summary", "aiSummary", "insights", "analysis", "result"]) ??
      pickFromSources(sources, ["summary_text", "ai_summary", "summaryText"]) ??
      copy ??
      undefined;

    const classification = pickListFromSources(sources, [
      "classification",
      "classifications",
      "categories",
      "category",
      "taxonomy",
      "classification_path",
      "classificationPath",
      "category_path",
    ]);
    const keywords = pickListFromSources(sources, ["keywords", "keywordList", "searchKeywords"]);
    const tags = pickListFromSources(sources, [
      "tags",
      "labels",
      "contentTags",
      "tagList",
      "content_tags",
    ]);
    const sentiment = extractSentimentFromSources(sources);
    const newAiAvailable = pickBooleanFromSources(sources, [
      "newAiAvailable",
      "new_ai_available",
      "pendingAiReview",
      "pending_ai_review",
      "aiUpdateAvailable",
      "ai_update_available",
    ]);
    const elementFieldsTagged = pickNumberFromSources(sources, [
      "fieldsTagged",
      "fieldCount",
      "totalFieldsTagged",
      "total_fields_tagged",
    ]);
    const elementReadability = pickNumberFromSources(sources, [
      "readabilityImproved",
      "readabilityDelta",
      "readabilityScoreDelta",
      "readability_improved",
      "readability_gain",
    ]);
    const elementErrors = pickNumberFromSources(sources, ["errorsFound", "errorCount", "errors"]);
    const elementStatus =
      pickFromSources(sources, [
        "status",
        "resultStatus",
        "result_status",
        "processingStatus",
        "processing_status",
        "elementStatus",
        "element_status",
        "enrichmentStatus",
        "enrichment_status",
        "action",
        "actionTaken",
        "resolution",
        "outcome",
      ]) ?? null;

    return {
      id,
      title,
      path: path ?? undefined,
      copy,
      summary: summaryValue,
      status: elementStatus,
      classification,
      keywords,
      tags,
      sentiment,
      newAiAvailable,
      meta: {
        fieldsTagged: elementFieldsTagged,
        readabilityDelta: elementReadability,
        errorsFound: elementErrors,
      },
    };
  });

  const aggregatedMetrics = { ...metrics };
  if (aggregatedMetrics.totalFieldsTagged == null) {
    const sum =
      elements.reduce((acc, element) => acc + (element.meta?.fieldsTagged ?? 0), 0) ||
      (elements.length ? elements.length : 0);
    aggregatedMetrics.totalFieldsTagged = sum || null;
  }
  if (aggregatedMetrics.readabilityDelta == null) {
    const firstDelta = elements.find((element) => element.meta?.readabilityDelta !== undefined)
      ?.meta?.readabilityDelta;
    aggregatedMetrics.readabilityDelta = firstDelta ?? null;
  }
  if (aggregatedMetrics.errorsFound == null) {
    const sumErrors = elements.reduce(
      (acc, element) => acc + (element.meta?.errorsFound ?? 0),
      0,
    );
    aggregatedMetrics.errorsFound = sumErrors || null;
  }

  return { metrics: aggregatedMetrics, elements };
};

const humanizePath = (path: string) => {
  const withoutRef = path.includes("::") ? path.split("::").pop()?.trim() ?? path : path;
  const segments = withoutRef.split("/").filter(Boolean);
  const lastSegment = segments[segments.length - 1] ?? withoutRef;
  return lastSegment.replace(/[-_]/g, " ").trim() || "Enriched section";
};

const buildGroupKey = (element: EnrichedElement, index: number) => {
  if (element.path?.trim()) {
    return element.path.trim();
  }
  if (element.title?.trim()) {
    return `title:${element.title.trim().toLowerCase()}`;
  }
  return `group-${index}`;
};

const buildGroupLabel = (key: string, fallback: EnrichedElement, index: number) => {
  if (key.startsWith("title:")) {
    return key.replace(/^title:/, "");
  }
  if (fallback.path?.trim()) {
    return humanizePath(fallback.path);
  }
  return fallback.title?.trim() ?? `Element group ${index + 1}`;
};

const shouldHideElement = (element: EnrichedElement): boolean => {
  const normalizedTitle = element.title?.toLowerCase().trim();
  if (!normalizedTitle) {
    return false;
  }
  return EXCLUDED_ITEM_TYPES.some((excluded) => {
    return (
      normalizedTitle === excluded ||
      normalizedTitle.startsWith(`${excluded}[`) ||
      normalizedTitle.startsWith(`${excluded}.`) ||
      normalizedTitle.includes(`${excluded}:`)
    );
  });
};

const SKIPPED_STATUS_KEYWORDS = [
  "SKIP",
  "SKIPPED",
  "IGNORED",
  "IGNORE",
  "NO_ENRICHMENT",
  "NOT_ENRICHED",
  "UNSUPPORTED",
  "EXCLUDED",
  "FILTERED",
];

const isSkippedStatus = (status?: string | null): boolean => {
  const normalized = status?.toUpperCase().replace(/[\s-]+/g, "_") ?? "";
  if (!normalized) return false;
  return SKIPPED_STATUS_KEYWORDS.some((keyword) => normalized.includes(keyword));
};

const isSkippedElement = (element: EnrichedElement): boolean => {
  return isSkippedStatus(element.status);
};

const FALLBACK_HISTORY: EnrichmentContext["statusHistory"] = [
  { status: "ENRICHMENT_TRIGGERED", timestamp: 0 },
  { status: "WAITING_FOR_RESULTS", timestamp: 0 },
];

const parseJson = async (response: Response) => {
  const rawBody = await response.text();
  const trimmed = rawBody.trim();
  let body: unknown = null;
  if (trimmed.length) {
    try {
      body = JSON.parse(trimmed);
    } catch {
      body = null;
    }
  }
  const looksLikeHtml = trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html");
  const friendlyRaw =
    looksLikeHtml && response.status
      ? `${response.status} ${response.statusText || ""}`.trim() || "HTML response returned."
      : rawBody;
  return { body, rawBody: friendlyRaw };
};

const unwrapProxyBody = (payload: unknown): unknown => {
  if (isRecord(payload) && "body" in payload) {
    return payload.body;
  }
  return payload;
};

const normalizeRevisionList = (payload: unknown): RevisionRecord[] => {
  const base = unwrapProxyBody(payload);
  if (Array.isArray(base)) {
    return base.filter(isRecord) as RevisionRecord[];
  }
  if (isRecord(base) && Array.isArray(base.revisions)) {
    return base.revisions.filter(isRecord) as RevisionRecord[];
  }
  return [];
};

const pickNumber = (value: unknown) => {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  return undefined;
};

const buildDefaultMetadata = (
  id: string,
  fallback?: EnrichmentContext["metadata"],
): EnrichmentContext["metadata"] => {
  return (
    fallback ?? {
      name: "Unknown dataset",
      size: 0,
      source: "Unknown source",
      uploadedAt: Date.now(),
      cleansedId: id,
    }
  );
};

const buildMetadataFromBackend = (
  backend: Record<string, unknown> | null,
  fallback: EnrichmentContext["metadata"],
  id: string,
): EnrichmentContext["metadata"] => {
  if (!backend) return fallback;
  const metadataRecord =
    backend.metadata && typeof backend.metadata === "object"
      ? (backend.metadata as Record<string, unknown>)
      : null;
  const next: EnrichmentContext["metadata"] = { ...fallback };
  if (metadataRecord) {
    next.name = pickString(metadataRecord.name) ?? next.name;
    next.source = pickString(metadataRecord.source) ?? next.source;
    next.cleansedId = pickString(metadataRecord.cleansedId) ?? next.cleansedId;
    next.sourceIdentifier =
      pickString(metadataRecord.sourceIdentifier) ?? next.sourceIdentifier;
    next.sourceType = pickString(metadataRecord.sourceType) ?? next.sourceType;
    next.locale = pickLocale(backend) ?? next.locale;
    next.pageId = pickPageId(backend) ?? next.pageId;
    const uploadedCandidate = pickNumber(metadataRecord.uploadedAt);
    if (uploadedCandidate) {
      next.uploadedAt = uploadedCandidate;
    }
    const sizeCandidate = pickNumber(metadataRecord.size);
    if (sizeCandidate !== undefined) {
      next.size = sizeCandidate;
    }
  }
  const derivedIdentifier =
    pickString(backend.sourceIdentifier) ??
    pickString(backend.sourceUri) ??
    next.sourceIdentifier;
  const derivedType =
    inferSourceType(
      pickString(backend.sourceType),
      derivedIdentifier ?? next.sourceIdentifier,
      next.sourceType,
    ) ?? next.sourceType;
  next.sourceIdentifier = derivedIdentifier ?? next.sourceIdentifier;
  next.sourceType = derivedType;
  next.source = describeSourceLabel(derivedType, next.source);
  next.cleansedId =
    pickString(backend.cleansedId) ??
    pickString(backend.cleansedDataStoreId) ??
    next.cleansedId ??
    id;
  return next;
};

const mapLocalContext = (local: EnrichmentContext | null): RemoteEnrichmentContext | null => {
  if (!local) return null;
  return {
    metadata: local.metadata,
    startedAt: local.startedAt,
    statusHistory: local.statusHistory,
  };
};

const extractSummary = (body: unknown): string => {
  if (typeof body === "string") return body;
  if (body && typeof body === "object") {
    const source = body as Record<string, unknown>;
    const summaryKeys = ["summary", "aiSummary", "insights", "result", "text", "content"];
    for (const key of summaryKeys) {
      const candidate = source[key];
      if (typeof candidate === "string" && candidate.trim()) {
        return candidate;
      }
    }
    return JSON.stringify(source, null, 2);
  }
  return "Awaiting enrichment results.";
};

const buildEditState = (element: EnrichedElement): ElementEditState => ({
  summary: element.summary ?? "",
  classification: serializeList(element.classification),
  keywords: serializeList(element.keywords),
  tags: serializeList(element.tags),
  isEditingInsights: false,
  isEditingMetadata: false,
  isSavingInsights: false,
  isSavingMetadata: false,
  isGeneratingInsights: false,
  isGeneratingMetadata: false,
});

export default function EnrichmentPageClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const queryId = searchParams.get("id");
  const localSnapshot = mapLocalContext(loadEnrichmentContext());

  const [context, setContext] = useState<RemoteEnrichmentContext | null>(localSnapshot);
  const [loading, setLoading] = useState<boolean>(!localSnapshot);
  const [error, setError] = useState<string | null>(null);
  const [statusFeedback, setStatusFeedback] = useState<Feedback>({ state: "idle" });
  const [summaryFeedback, setSummaryFeedback] = useState<SummaryFeedback>({ state: "idle" });
  const [enrichmentResult, setEnrichmentResult] = useState<EnrichmentOverview | null>(null);
  const [rawSummary, setRawSummary] = useState<string | null>(null);
  const [expandedElementId, setExpandedElementId] = useState<string | null>(null);
  const [elementEdits, setElementEdits] = useState<Record<string, ElementEditState>>({});
  const [historyOpen, setHistoryOpen] = useState<Record<string, boolean>>({});
  const [historyRecords, setHistoryRecords] = useState<Record<string, RevisionRecord[]>>({});
  const [historyLoading, setHistoryLoading] = useState<Record<string, boolean>>({});
  const [historyError, setHistoryError] = useState<Record<string, string | undefined>>({});
  const [historyRestoring, setHistoryRestoring] = useState<Record<string, string | null>>({});
  const [activeId, setActiveId] = useState<string | null>(
    queryId ?? localSnapshot?.metadata.cleansedId ?? null,
  );
  const [hydrated, setHydrated] = useState(false);
  const [openSubSections, setOpenSubSections] = useState<Record<string, Set<string>>>({});

  const toggleSubSection = (elementId: string, sectionId: string) => {
    setOpenSubSections((prev) => {
      const next = { ...prev };
      const sections = new Set(prev[elementId] || ["insights"]); // Default insights open
      if (sections.has(sectionId)) {
        sections.delete(sectionId);
      } else {
        sections.add(sectionId);
      }
      next[elementId] = sections;
      return next;
    });
  };

  useEffect(() => {
    setHydrated(true);
  }, []);

  useEffect(() => {
    const fallbackId = localSnapshot?.metadata.cleansedId ?? null;
    setActiveId(queryId ?? fallbackId);
  }, [queryId, localSnapshot?.metadata.cleansedId]);

  const fetchRemoteStatus = async (id: string): Promise<RemoteEnrichmentContext> => {
    const response = await fetch(`/api/ingestion/enrichment/status?id=${encodeURIComponent(id)}`);
    const { body, rawBody } = await parseJson(response);
    if (!response.ok) {
      throw new Error(
        (body as Record<string, unknown>)?.error as string ??
          rawBody ??
          "Backend rejected the enrichment status request.",
      );
    }
    const proxyPayload = (body as Record<string, unknown>) ?? {};
    let backendRecord: Record<string, unknown> | null = null;
    if (proxyPayload.body && typeof proxyPayload.body === "object") {
      backendRecord = proxyPayload.body as Record<string, unknown>;
    } else if (!("body" in proxyPayload) && typeof proxyPayload === "object") {
      backendRecord = proxyPayload;
    }
    const fallbackMetadata = buildDefaultMetadata(id, localSnapshot?.metadata ?? undefined);
    const mergedMetadata = buildMetadataFromBackend(backendRecord, fallbackMetadata, id);
    const backendHistory = Array.isArray(
      backendRecord?.["statusHistory"] as { status: string; timestamp: number }[] | undefined,
    )
      ? (backendRecord?.["statusHistory"] as { status: string; timestamp: number }[])
      : null;

    const backendStatus = pickString(backendRecord?.status) ?? pickString(proxyPayload?.status);
    const derivedHistory =
      backendHistory && backendHistory.length
        ? backendHistory
        : backendStatus
          ? [{ status: backendStatus, timestamp: Date.now() }]
          : FALLBACK_HISTORY;

    const remoteContext: RemoteEnrichmentContext = {
      metadata: mergedMetadata,
      startedAt:
        pickNumber(backendRecord?.startedAt) ??
        pickNumber(proxyPayload.startedAt) ??
        Date.now(),
      statusHistory: derivedHistory,
    };

    saveEnrichmentContext(remoteContext);

    return remoteContext;
  };

  const loadContext = async (
    id: string | null,
    options: { showSpinner?: boolean; rethrowOnError?: boolean } = {},
  ) => {
    const { showSpinner = true, rethrowOnError = false } = options;
    if (!id) {
      setLoading(false);
      setError("Provide a cleansed ID via the URL or trigger a new run.");
      setContext(localSnapshot);
      return null;
    }
    if (showSpinner) {
      setLoading(true);
    }
    setError(null);
    try {
      const remote = await fetchRemoteStatus(id);
      setContext(remote);
      return remote;
    } catch (statusError) {
      setError(statusError instanceof Error ? statusError.message : "Unable to load enrichment status.");
      if (!showSpinner && localSnapshot) {
        setContext(localSnapshot);
      } else if (!localSnapshot) {
        setContext(null);
      }
      if (rethrowOnError) {
        throw statusError;
      }
      return null;
    } finally {
      if (showSpinner) {
        setLoading(false);
      }
    }
  };

  const fetchSummary = async (id: string, showLoading = true) => {
    if (showLoading) {
      setSummaryFeedback({ state: "loading" });
    }
    try {
      const response = await fetch(`/api/ingestion/enrichment/result?id=${encodeURIComponent(id)}`);
      const { body, rawBody } = await parseJson(response);
      if (!response.ok) {
        if (response.status === 404) {
          setEnrichmentResult(null);
          setRawSummary("Awaiting enrichment results.");
          setSummaryFeedback({ state: "idle" });
          return;
        }
        throw new Error(
          (body as Record<string, unknown>)?.error as string ??
            rawBody ??
            "Backend rejected the enrichment result request.",
        );
      }
      const proxyPayload = (body as Record<string, unknown>) ?? {};
      const normalized = normalizeEnrichmentResult(proxyPayload);
      setEnrichmentResult(normalized);
      const summarySource =
        proxyPayload.body ?? proxyPayload.rawBody ?? rawBody ?? "Awaiting enrichment results.";
      if (!normalized.elements.length) {
        setRawSummary(extractSummary(summarySource));
      } else {
        setRawSummary(null);
        setContext((previous) => {
          return previous;
        });
      }
      setSummaryFeedback({ state: "idle" });
    } catch (summaryError) {
      setEnrichmentResult(null);
      setRawSummary(null);
      setSummaryFeedback({
        state: "error",
        message: summaryError instanceof Error ? summaryError.message : "Unable to load enrichment results.",
      });
    }
  };

  const ensureElementEditState = (element: EnrichedElement, updates?: Partial<ElementEditState>) => {
    setElementEdits((previous) => {
      const existing = previous[element.id] ?? buildEditState(element);
      return {
        ...previous,
        [element.id]: { ...existing, ...updates },
      };
    });
  };

  const applyUpdatedElement = (updated: EnrichedElement | null) => {
    if (!updated) return;
    setEnrichmentResult((previous) => {
      if (!previous) return previous;
      return {
        ...previous,
        elements: previous.elements.map((element) =>
          element.id === updated.id ? updated : element,
        ),
      };
    });
  };

  const normalizeUpdatedElement = (payload: unknown): EnrichedElement | null => {
    const record =
      isRecord(payload) && isRecord(payload.element)
        ? (payload.element as Record<string, unknown>)
        : isRecord(payload)
          ? payload
          : null;
    if (!record) {
      return null;
    }
    const normalized = normalizeEnrichmentResult({ elements: [record] });
    return normalized.elements[0] ?? null;
  };

  const submitElementUpdate = async (
    elementId: string,
    payload: Record<string, unknown>,
    method: "PUT" | "POST",
  ) => {
    const response = await fetch(`/api/ingestion/enrichment/element/${elementId}`, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const { body, rawBody } = await parseJson(response);
    if (!response.ok) {
      const responseBody = body as Record<string, unknown> | null;
      const nestedBody =
        responseBody && typeof responseBody.body === "object"
          ? (responseBody.body as Record<string, unknown>)
          : null;
      throw new Error(
        (responseBody?.error as string) ??
          (nestedBody?.error as string) ??
          rawBody ??
          "Unable to update enrichment content.",
      );
    }
    return unwrapProxyBody(body);
  };

  const handleSaveInsights = async (element: EnrichedElement) => {
    const draft = elementEdits[element.id] ?? buildEditState(element);
    ensureElementEditState(element, { isSavingInsights: true, error: undefined });
    try {
      const payload = {
        summary: draft.summary,
        classification: draft.classification,
        keywords: element.keywords,
        tags: element.tags,
        editedBy: "ui",
      };
      const payloadResult = await submitElementUpdate(element.id, payload, "PUT");
      const updated = normalizeUpdatedElement(payloadResult);
      if (updated) {
        applyUpdatedElement(updated);
        setElementEdits((previous) => {
          const existing = previous[element.id] ?? buildEditState(updated);
          const keepMetadata = existing.isEditingMetadata;
          return {
            ...previous,
            [updated.id]: {
              ...existing,
              summary: updated.summary ?? "",
              classification: serializeList(updated.classification),
              keywords: keepMetadata ? existing.keywords : serializeList(updated.keywords),
              tags: keepMetadata ? existing.tags : serializeList(updated.tags),
              isEditingInsights: false,
            },
          };
        });
      } else {
        ensureElementEditState(element, { isEditingInsights: false });
      }
    } catch (saveError) {
      ensureElementEditState(element, {
        error: saveError instanceof Error ? saveError.message : "Unable to save edits.",
      });
    } finally {
      ensureElementEditState(element, { isSavingInsights: false });
    }
  };

  const handleSaveMetadata = async (element: EnrichedElement) => {
    const draft = elementEdits[element.id] ?? buildEditState(element);
    ensureElementEditState(element, { isSavingMetadata: true, error: undefined });
    try {
      const payload = {
        summary: element.summary ?? "",
        classification: serializeList(element.classification),
        keywords: parseInputList(draft.keywords),
        tags: parseInputList(draft.tags),
        editedBy: "ui",
      };
      const payloadResult = await submitElementUpdate(element.id, payload, "PUT");
      const updated = normalizeUpdatedElement(payloadResult);
      if (updated) {
        applyUpdatedElement(updated);
        setElementEdits((previous) => {
          const existing = previous[element.id] ?? buildEditState(updated);
          const keepInsights = existing.isEditingInsights;
          return {
            ...previous,
            [updated.id]: {
              ...existing,
              summary: keepInsights ? existing.summary : updated.summary ?? "",
              classification: keepInsights
                ? existing.classification
                : serializeList(updated.classification),
              keywords: serializeList(updated.keywords),
              tags: serializeList(updated.tags),
              isEditingMetadata: false,
            },
          };
        });
      } else {
        ensureElementEditState(element, { isEditingMetadata: false });
      }
    } catch (saveError) {
      ensureElementEditState(element, {
        error: saveError instanceof Error ? saveError.message : "Unable to save edits.",
      });
    } finally {
      ensureElementEditState(element, { isSavingMetadata: false });
    }
  };

  const updateEditStateFromGenerated = (
    elementId: string,
    updated: EnrichedElement,
    fields: EnrichmentField[],
  ) => {
    setElementEdits((previous) => {
      const existing = previous[elementId] ?? buildEditState(updated);
      const nextState = { ...existing };
      if (fields.includes("summary")) {
        nextState.summary = updated.summary ?? "";
      }
      if (fields.includes("classification")) {
        nextState.classification = serializeList(updated.classification);
      }
      if (fields.includes("keywords")) {
        nextState.keywords = serializeList(updated.keywords);
      }
      if (fields.includes("tags")) {
        nextState.tags = serializeList(updated.tags);
      }
      return { ...previous, [updated.id]: nextState };
    });
  };

  const handleGenerateFields = async (element: EnrichedElement, fields: EnrichmentField[]) => {
    const isInsights = fields.some((field) => field === "summary" || field === "classification");
    const isMetadata = fields.some((field) => field === "keywords" || field === "tags");
    const startFlags: Partial<ElementEditState> = { error: undefined };
    if (isInsights) startFlags.isGeneratingInsights = true;
    if (isMetadata) startFlags.isGeneratingMetadata = true;
    ensureElementEditState(element, startFlags);
    try {
      const payloadResult = await submitElementUpdate(
        element.id,
        { fields },
        "POST",
      );
      const updated = normalizeUpdatedElement(payloadResult);
      if (updated) {
        applyUpdatedElement(updated);
        updateEditStateFromGenerated(element.id, updated, fields);
      }
    } catch (generateError) {
      ensureElementEditState(element, {
        error: generateError instanceof Error ? generateError.message : "Unable to regenerate content.",
      });
    } finally {
      const endFlags: Partial<ElementEditState> = {};
      if (isInsights) endFlags.isGeneratingInsights = false;
      if (isMetadata) endFlags.isGeneratingMetadata = false;
      ensureElementEditState(element, endFlags);
    }
  };

  const fetchRevisionHistory = async (elementId: string) => {
    setHistoryLoading((previous) => ({ ...previous, [elementId]: true }));
    setHistoryError((previous) => ({ ...previous, [elementId]: undefined }));
    try {
      const response = await fetch(
        `/api/ingestion/enrichment/element/${elementId}/revisions`,
      );
      const { body, rawBody } = await parseJson(response);
      if (!response.ok) {
        throw new Error(
          (body as Record<string, unknown>)?.error as string ??
            rawBody ??
            "Unable to load revision history.",
        );
      }
      const revisions = normalizeRevisionList(body);
      setHistoryRecords((previous) => ({ ...previous, [elementId]: revisions }));
    } catch (historyError) {
      setHistoryError((previous) => ({
        ...previous,
        [elementId]:
          historyError instanceof Error
            ? historyError.message
            : "Unable to load revision history.",
      }));
    } finally {
      setHistoryLoading((previous) => ({ ...previous, [elementId]: false }));
    }
  };

  const toggleHistoryPanel = (elementId: string) => {
    setHistoryOpen((previous) => {
      const nextOpen = !previous[elementId];
      return { ...previous, [elementId]: nextOpen };
    });
    if (!historyOpen[elementId] && !(historyRecords[elementId]?.length ?? 0)) {
      fetchRevisionHistory(elementId).catch(() => undefined);
    }
  };

  const handleRestoreRevision = async (element: EnrichedElement, revision: RevisionRecord) => {
    if (!revision.id) return;
    setHistoryRestoring((previous) => ({ ...previous, [element.id]: revision.id }));
    setHistoryError((previous) => ({ ...previous, [element.id]: undefined }));
    try {
      const response = await fetch(
        `/api/ingestion/enrichment/element/${element.id}/restore/${revision.id}`,
        { method: "POST" },
      );
      const { body, rawBody } = await parseJson(response);
      if (!response.ok) {
        throw new Error(
          (body as Record<string, unknown>)?.error as string ??
            rawBody ??
            "Unable to restore revision.",
        );
      }
      const payload = unwrapProxyBody(body);
      const updated = normalizeUpdatedElement(payload);
      if (updated) {
        applyUpdatedElement(updated);
        updateEditStateFromGenerated(element.id, updated, [
          "summary",
          "classification",
          "keywords",
          "tags",
        ]);
      }
      await fetchRevisionHistory(element.id);
    } catch (restoreError) {
      setHistoryError((previous) => ({
        ...previous,
        [element.id]:
          restoreError instanceof Error
            ? restoreError.message
            : "Unable to restore revision.",
      }));
    } finally {
      setHistoryRestoring((previous) => ({ ...previous, [element.id]: null }));
    }
  };

  const skippedElementsCount = useMemo(() => {
    if (!enrichmentResult?.elements.length) return 0;
    return enrichmentResult.elements.filter((element) => isSkippedElement(element)).length;
  }, [enrichmentResult?.elements]);

  const filteredElements = useMemo(() => {
    if (!enrichmentResult?.elements.length) return [];
    return enrichmentResult.elements.filter(
      (element) => !shouldHideElement(element) && !isSkippedElement(element),
    );
  }, [enrichmentResult?.elements]);

  const groupedElements = useMemo(() => {
    if (!filteredElements.length) return [];
    const groups = new Map<string, { label: string; elements: EnrichedElement[] }>();
    filteredElements.forEach((element, index) => {
      const key = buildGroupKey(element, index);
      const existing = groups.get(key);
      if (existing) {
        existing.elements.push(element);
        return;
      }
      groups.set(key, {
        label: buildGroupLabel(key, element, index),
        elements: [element],
      });
    });
    return Array.from(groups.entries()).map(([key, value]) => ({
      id: key || `group-${value.label}-${value.elements.length}`,
      label: value.label,
      elements: value.elements,
    }));
  }, [filteredElements]);

  const [expandedGroups, setExpandedGroups] = useState(new Set<string>());

  useEffect(() => {
    if (!groupedElements.length) {
      setExpandedGroups(new Set());
      setExpandedElementId(null);
      return;
    }
    setExpandedGroups(new Set(groupedElements.map((group) => group.id)));
    setExpandedElementId((current) => {
      if (current && filteredElements.some((element) => element.id === current)) {
        return current;
      }
      return filteredElements[0]?.id ?? null;
    });
  }, [groupedElements, filteredElements]);

  const toggleGroup = (groupId: string) => {
    setExpandedGroups((previous) => {
      const next = new Set(previous);
      if (next.has(groupId)) {
        next.delete(groupId);
      } else {
        next.add(groupId);
      }
      return next;
    });
  };

  const toggleElementDetails = (elementId: string) => {
    setExpandedElementId((current) => (current === elementId ? null : elementId));
  };

  useEffect(() => {
    loadContext(activeId).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId]);

  useEffect(() => {
    if (context?.metadata.cleansedId) {
      fetchSummary(context.metadata.cleansedId, true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [context?.metadata.cleansedId]);

  const statusHistory = context?.statusHistory?.length
    ? context.statusHistory
    : FALLBACK_HISTORY;
  const currentStatus = statusHistory[statusHistory.length - 1]?.status ?? "WAITING_FOR_RESULTS";

  useEffect(() => {
    if (
      !activeId ||
      currentStatus === "ENRICHMENT_COMPLETE" ||
      currentStatus === "ENRICHED_NO_ITEMS_TO_PROCESS" ||
      currentStatus === "ERROR"
    ) {
      return;
    }

    const interval = setInterval(() => {
      loadContext(activeId, { showSpinner: false }).catch(() => undefined);
      if (context?.metadata.cleansedId) {
        fetchSummary(context.metadata.cleansedId, false).catch(() => undefined);
      }
    }, 5000);

    return () => clearInterval(interval);
  }, [activeId, currentStatus, context?.metadata.cleansedId, loadContext]);

  const statusMeta = STATUS_COLORS[currentStatus] ?? {
    className: "text-slate-700",
    dot: "bg-slate-300",
    background: "bg-slate-100",
  };

  const progress = useMemo(() => {
    const statuses = [
      "ENRICHMENT_TRIGGERED",
      "WAITING_FOR_RESULTS",
      "ENRICHMENT_RUNNING",
      "PARTIALLY_ENRICHED",
      "ENRICHMENT_COMPLETE",
    ];

    if (currentStatus === "ENRICHMENT_COMPLETE" || currentStatus === "ENRICHED_NO_ITEMS_TO_PROCESS") return 100;

    const statusIndex = statuses.indexOf(currentStatus);
    const baseProgress = statusIndex >= 0 ? ((statusIndex + 1) / statuses.length) * 100 : 20;

    if (enrichmentResult?.elements && enrichmentResult.elements.length > 0) {
      const total = enrichmentResult.elements.length;
      const enriched = enrichmentResult.elements.filter(e =>
        e.summary || (e.status && !isSkippedStatus(e.status))
      ).length;

      const enrichmentRatio = enriched / total;

      if (enriched > 0) {
        return Math.max(baseProgress, 60 + (enrichmentRatio * 35));
      }
    }

    return baseProgress;
  }, [currentStatus, enrichmentResult]);

  const metrics = enrichmentResult?.metrics ?? {};
  const backendTotalFieldsTagged =
    metrics.totalFieldsTagged !== null && metrics.totalFieldsTagged !== undefined
      ? Math.round(metrics.totalFieldsTagged)
      : null;
  const totalElementsCount = enrichmentResult ? enrichmentResult.elements.length : null;
  const enrichedElementsCount =
    totalElementsCount !== null ? Math.max(0, totalElementsCount - skippedElementsCount) : null;
  const derivedTotalFieldsTagged =
    totalElementsCount !== null && enrichedElementsCount !== null
      ? enrichedElementsCount + skippedElementsCount
      : null;
  const totalFieldsTagged =
    derivedTotalFieldsTagged !== null ? derivedTotalFieldsTagged : backendTotalFieldsTagged;
  const totalFieldsBreakdown =
    derivedTotalFieldsTagged !== null && enrichedElementsCount !== null
      ? `${enrichedElementsCount} enriched${
          skippedElementsCount ? ` · ${skippedElementsCount} skipped` : ""
        }`
      : null;
  const readabilityDelta =
    metrics.readabilityDelta !== null && metrics.readabilityDelta !== undefined
      ? metrics.readabilityDelta
      : null;
  const errorsFoundMetric =
    metrics.errorsFound !== null && metrics.errorsFound !== undefined ? metrics.errorsFound : null;
  const normalizedReadability =
    readabilityDelta !== null && Number.isFinite(readabilityDelta)
      ? Math.abs(readabilityDelta) <= 1
        ? readabilityDelta * 100
        : readabilityDelta
      : null;
  const readabilityDisplay =
    normalizedReadability !== null
      ? `${normalizedReadability > 0 ? "+" : ""}${Math.round(normalizedReadability)}%`
      : null;
  const errorsDisplay =
    errorsFoundMetric !== null ? Math.max(0, Math.round(errorsFoundMetric)) : null;

  const renderChipList = (items: string[], emptyLabel: string) => {
    if (!items.length) {
      return <p className="text-sm text-slate-500">{emptyLabel}</p>;
    }
    return (
      <div className="flex flex-wrap gap-2">
        {items.map((item) => (
          <span
            key={item}
            className="inline-flex items-center rounded-full bg-slate-100 px-2.5 py-1 text-xs font-semibold text-slate-700"
          >
            {item}
          </span>
        ))}
      </div>
    );
  };

  const handleRefreshStatus = async () => {
    if (!activeId) {
      setStatusFeedback({
        state: "error",
        message: "Cleansed ID missing; re-run cleansing before enrichment.",
      });
      return;
    }
    setStatusFeedback({ state: "loading" });
    try {
      await loadContext(activeId, { showSpinner: false, rethrowOnError: true });
      setStatusFeedback({ state: "success", message: "Status refreshed." });
      await fetchSummary(activeId, false);
    } catch (refreshError) {
      setStatusFeedback({
        state: "error",
        message: refreshError instanceof Error ? refreshError.message : "Unable to refresh status.",
      });
    }
  };

  if (loading || !hydrated) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 px-6 py-20">
        <div className="max-w-xl rounded-3xl border border-slate-200 bg-white p-10 text-center shadow-sm">
          <p className="text-xs uppercase tracking-wide text-slate-400">Enrichment</p>
          <h1 className="mt-2 text-2xl font-semibold text-slate-900">Loading status…</h1>
          <p className="mt-4 text-sm text-slate-500">
            Fetching enrichment details from the backend. One moment please.
          </p>
        </div>
      </div>
    );
  }

  if (!context) {
    return (
      <PipelineShell currentStep="enrichment" showTracker={false}>
        <div className="flex h-[calc(100vh-64px)] items-center justify-center bg-gray-50/50 p-8">
          <div className="max-w-md w-full rounded-2xl border border-gray-200 bg-white p-10 text-center shadow-sm">
            <h2 className="text-xl font-bold text-gray-900">{error ?? "Enrichment data not found"}</h2>
            <p className="mt-4 text-sm text-gray-500">
              Trigger enrichment from the cleansing screen to review progress here.
            </p>
            <button
              type="button"
              onClick={() => router.push("/cleansing")}
              className="mt-8 btn-primary w-full"
            >
              Back to Cleansing
            </button>
          </div>
        </div>
      </PipelineShell>
    );
  }

  const sourceLabel = describeSourceLabel(
    context.metadata.sourceType ?? context.metadata.source,
    context.metadata.source,
  );
  const sourceIdentifier = context.metadata.sourceIdentifier ?? "—";

  return (
    <PipelineShell currentStep="enrichment">
      <div className="min-h-[calc(100vh-4rem)] bg-background">
        <section className="border-b border-slate-200 bg-white">
          <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-3 px-4 py-4 sm:px-6 sm:py-6 lg:px-8">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between text-left">
              <div className="space-y-1 sm:space-y-2 min-w-0">
                <h1 className="text-2xl sm:text-3xl font-bold text-black">Enrichment</h1>
                <p className="text-xs sm:text-sm font-medium text-slate-500 lg:max-w-2xl break-words">
                  Review AI insights and generated metadata for {context.metadata.name}.
                </p>
              </div>
            </div>
          </div>
        </section>

        <main className="mx-auto grid max-w-[1600px] gap-6 px-4 py-6 sm:px-6 sm:py-8 lg:grid-cols-12 items-stretch overflow-x-hidden md:overflow-x-visible">
          <div className="lg:col-span-8 flex flex-col gap-8 min-w-0">
            <section className="bg-white rounded-3xl border border-slate-200 p-6 lg:p-8 shadow-sm">
              <div className="flex flex-col gap-8 lg:flex-row lg:items-center lg:justify-between mb-8">
                <div className="flex-1">
                  <p className="text-xs uppercase tracking-wide text-gray-400 font-bold mb-2">Status</p>
                  <div className={`inline-flex items-center gap-2 rounded-full px-4 py-2 text-sm font-bold ${statusMeta.background} ${statusMeta.className}`}>
                    <span className={`h-2 w-2 rounded-full ${statusMeta.dot}`} />
                    {STATUS_LABELS[currentStatus] ?? currentStatus}
                  </div>
                </div>
                <div className="w-full lg:max-w-md">
                  <p className="text-xs uppercase tracking-wide text-gray-400 font-bold mb-2">
                    Pipeline progress
                  </p>
                  <div className="h-2 rounded-full bg-gray-100 overflow-hidden">
                    <div
                      className="h-full bg-primary transition-all duration-1000 ease-out"
                      style={{ width: `${progress}%` }}
                    />
                  </div>
                  <div className="mt-2 flex items-center justify-between">
                    <p className="text-xs text-gray-500 font-bold">{Math.round(progress)}% complete</p>
                    <button
                      type="button"
                      onClick={handleRefreshStatus}
                      className="text-xs font-bold text-slate-500 hover:text-black transition-colors"
                    >
                      Refresh Status
                    </button>
                  </div>
                </div>
              </div>
            </section>

            <section className="bg-white rounded-3xl border border-slate-200 p-6 lg:p-10 shadow-sm">
              <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between mb-8">
                <div>
                  <p className="text-xs uppercase tracking-wide text-gray-400 font-bold">Insights</p>
                  <h2 className="text-2xl font-bold text-gray-900">AI summary preview</h2>
                </div>
              </div>

              {summaryFeedback.state === "loading" ? (
                <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50 py-12 text-center text-sm text-slate-500">
                  Loading enrichment summary…
                </div>
              ) : summaryFeedback.state === "error" ? (
                <div className="rounded-2xl border border-amber-200 bg-amber-50 p-6">
                  <p className="font-bold text-amber-800">Unable to load enrichment summary.</p>
                  <p className="mt-1 text-sm text-amber-900/80">{summaryFeedback.message}</p>
                  <button
                    type="button"
                    onClick={() => context.metadata.cleansedId && fetchSummary(context.metadata.cleansedId, true)}
                    className="mt-4 rounded-full bg-amber-600 px-4 py-2 text-xs font-bold text-white shadow-sm hover:bg-amber-700 transition-colors"
                  >
                    Retry
                  </button>
                </div>
              ) : enrichmentResult?.elements.length ? (
                <div className="space-y-6">
                  <p className="text-xs uppercase tracking-wide text-gray-400 font-bold">Enriched sections</p>
                  <div className="max-h-[1000px] space-y-4 overflow-y-auto pr-2 custom-scrollbar">
                    {groupedElements.map((group) => {
                      const isExpanded = expandedGroups.has(group.id);
                      return (
                        <div key={group.id} className="rounded-2xl border border-slate-100 bg-slate-50/30">
                          <button
                            type="button"
                            onClick={() => toggleGroup(group.id)}
                            className="flex w-full items-center justify-between gap-4 px-6 py-4 text-left"
                          >
                            <div className="flex items-center gap-3">
                              <span className="text-sm font-bold text-slate-800">{group.label}</span>
                              <span className="rounded-full bg-white border border-slate-100 px-2 py-0.5 text-[10px] font-bold text-slate-400">
                                {group.elements.length}
                              </span>
                            </div>
                            {isExpanded ? (
                              <ChevronDownIcon className="size-4 text-slate-400" />
                            ) : (
                              <ChevronRightIcon className="size-4 text-slate-400" />
                            )}
                          </button>
                          {isExpanded && (
                            <div className="space-y-3 px-6 pb-6">
                              {group.elements.map((element, elementIndex) => {
                                const isDetailVisible = expandedElementId === element.id;
                                const sourcePath = element.path?.trim();
                                const normalizedTitle = element.title?.trim() ?? "";
                                const primaryLabel =
                                  normalizedTitle.length > 0
                                    ? normalizedTitle
                                    : sourcePath
                                      ? humanizePath(sourcePath)
                                      : `Field ${elementIndex + 1}`;
                                const editState = elementEdits[element.id] ?? buildEditState(element);
                                const insightsBusy = editState.isSavingInsights || editState.isGeneratingInsights;
                                const metadataBusy = editState.isSavingMetadata || editState.isGeneratingMetadata;
                                const historyIsOpen = historyOpen[element.id] ?? false;
                                const revisionList = historyRecords[element.id] ?? [];
                                const historyBusy = historyLoading[element.id] ?? false;
                                const historyMessage = historyError[element.id];
                                const restoringRevisionId = historyRestoring[element.id] ?? null;
                                const latestAiRevisionId = revisionList.find((revision) => {
                                  const source = normalizeRevisionSource(revision.source);
                                  return source === "AI" || source === "REGENERATE";
                                })?.id;
                                const latestUserRevisionId = revisionList.find((revision) => {
                                  return normalizeRevisionSource(revision.source) === "USER";
                                })?.id;

                                const currentOpenSections = openSubSections[element.id] || new Set(["insights"]);
                                const isInsightsOpen = currentOpenSections.has("insights");
                                const isMetadataOpen = currentOpenSections.has("metadata");
                                const isToneOpen = currentOpenSections.has("tone");

                                return (
                                  <div
                                    key={element.id}
                                    className="rounded-xl border border-slate-200 bg-white shadow-sm overflow-hidden"
                                  >
                                    <button
                                      type="button"
                                      onClick={() => toggleElementDetails(element.id)}
                                      className="flex w-full items-center justify-between gap-4 px-4 py-3 text-left hover:bg-slate-50/50 transition-colors"
                                    >
                                      <div className="flex flex-col flex-1 min-w-0">
                                        <div className="flex items-center gap-2">
                                          <p className="text-sm font-bold text-slate-900 truncate">
                                            {primaryLabel}
                                          </p>
                                          {element.newAiAvailable && (
                                            <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-bold text-amber-700">
                                              NEW AI
                                            </span>
                                          )}
                                        </div>
                                        {sourcePath && (
                                          <p className="truncate text-[11px] text-slate-400 font-medium">
                                            {sourcePath}
                                          </p>
                                        )}
                                      </div>
                                      {isDetailVisible ? (
                                        <ChevronDownIcon className="size-4 text-slate-400" />
                                      ) : (
                                        <ChevronRightIcon className="size-4 text-slate-400" />
                                      )}
                                    </button>
                                    {isDetailVisible && (
                                      <div className="space-y-6 sm:space-y-8 border-t border-slate-100 bg-slate-50/30 p-4 sm:p-8">
                                        <div>
                                          <p className="text-[10px] uppercase tracking-widest text-slate-400 font-black mb-3">
                                            Enriched Copy
                                          </p>
                                          <p className="text-sm text-slate-700 leading-relaxed break-words">
                                            {element.copy ?? "No enriched copy provided yet."}
                                          </p>
                                        </div>

                                        <div className="grid gap-4 lg:gap-6 lg:grid-cols-1 xl:grid-cols-1 items-start">
                                          {/* Content Insights Card */}
                                          <div className="bg-white rounded-2xl lg:rounded-3xl border border-slate-200 shadow-sm flex flex-col overflow-hidden h-auto">
                                            <button
                                              type="button"
                                              onClick={() => toggleSubSection(element.id, "insights")}
                                              className="flex w-full items-center justify-between p-4 lg:p-6 lg:pointer-events-none text-left"
                                            >
                                              <div className="flex flex-col gap-1">
                                                <p className="text-[10px] uppercase tracking-widest text-slate-400 font-black">Content Insights</p>
                                                <h3 className="text-base lg:text-lg font-bold text-slate-900 leading-tight">Summary</h3>
                                              </div>
                                              <div className="lg:hidden">
                                                {isInsightsOpen ? (
                                                  <ChevronDownIcon className="size-4 text-slate-400" />
                                                ) : (
                                                  <ChevronRightIcon className="size-4 text-slate-400" />
                                                )}
                                              </div>
                                            </button>

                                            <div className={clsx(
                                              "flex-col px-4 pb-4 lg:px-6 lg:pb-6 lg:flex",
                                              isInsightsOpen ? "flex" : "hidden"
                                            )}>
                                              <div className="flex flex-col gap-4 mb-6">
                                                <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
                                                  {!editState.isEditingInsights && (
                                                    <button
                                                      onClick={(e) => {
                                                        e.stopPropagation();
                                                        ensureElementEditState(element, { isEditingInsights: true, error: undefined });
                                                      }}
                                                      className="rounded-full px-4 py-1.5 text-xs font-bold border border-slate-200 text-slate-600 hover:bg-slate-50 transition-colors shrink-0"
                                                    >
                                                      Edit
                                                    </button>
                                                  )}
                                                  {editState.isEditingInsights && (
                                                    <div className="flex flex-wrap items-center gap-2">
                                                      <button
                                                        onClick={() => handleSaveInsights(element)}
                                                        disabled={insightsBusy}
                                                        className="rounded-full bg-primary px-4 py-1.5 text-xs font-bold text-white hover:bg-accent transition-colors disabled:opacity-50"
                                                      >
                                                        {editState.isSavingInsights ? "..." : "Save"}
                                                      </button>
                                                      <button
                                                        onClick={() => ensureElementEditState(element, { isEditingInsights: false })}
                                                        className="rounded-full bg-white px-4 py-1.5 text-xs font-bold text-slate-500 border border-slate-200 hover:bg-slate-50 transition-colors"
                                                      >
                                                        Cancel
                                                      </button>
                                                      <button
                                                        onClick={() => handleGenerateFields(element, ["summary", "classification"])}
                                                        disabled={insightsBusy}
                                                        className="rounded-full bg-primary-soft px-4 py-1.5 text-xs font-bold text-primary hover:bg-primary/10 transition-colors disabled:opacity-50"
                                                      >
                                                        {editState.isGeneratingInsights ? "..." : "Generate all"}
                                                      </button>
                                                    </div>
                                                  )}
                                                </div>
                                              </div>

                                              <div className="space-y-6 flex-1">
                                              <div>
                                                <div className="flex items-center justify-between mb-2">
                                                  <p className="text-[10px] font-black text-slate-400 uppercase tracking-widest">Summary</p>
                                                  {editState.isEditingInsights && (
                                                    <button
                                                      onClick={() => handleGenerateFields(element, ["summary"])}
                                                      disabled={insightsBusy}
                                                      className="rounded-lg bg-primary-soft px-2 py-1 text-[10px] font-bold text-primary hover:bg-primary/10 transition-colors"
                                                    >
                                                      Generate
                                                    </button>
                                                  )}
                                                </div>
                                                {editState.isEditingInsights ? (
                                                  <textarea
                                                    value={editState.summary}
                                                    onChange={(e) => ensureElementEditState(element, { summary: e.target.value })}
                                                    className="w-full text-base lg:text-sm bg-slate-50 border border-slate-200 rounded-2xl p-4 focus:outline-none focus:ring-2 focus:ring-primary/20 min-h-[120px]"
                                                    placeholder="AI Summary"
                                                  />
                                                ) : (
                                                  <p className="text-sm text-slate-600 leading-relaxed bg-slate-50/50 rounded-2xl p-3 sm:p-4 break-words">
                                                    {element.summary ?? "—"}
                                                  </p>
                                                )}
                                              </div>

                                              <div>
                                                <div className="flex items-center justify-between mb-2">
                                                  <p className="text-[10px] font-black text-slate-400 uppercase tracking-widest">Classification</p>
                                                  {editState.isEditingInsights && (
                                                    <button
                                                      onClick={() => handleGenerateFields(element, ["classification"])}
                                                      disabled={insightsBusy}
                                                      className="rounded-lg bg-primary-soft px-2 py-1 text-[10px] font-bold text-primary hover:bg-primary/10 transition-colors"
                                                    >
                                                      Generate
                                                    </button>
                                                  )}
                                                </div>
                                                {editState.isEditingInsights ? (
                                                  <input
                                                    value={editState.classification}
                                                    onChange={(e) => ensureElementEditState(element, { classification: e.target.value })}
                                                    className="w-full text-base lg:text-sm bg-slate-50 border border-slate-200 rounded-xl px-4 py-2 focus:outline-none focus:ring-2 focus:ring-primary/20"
                                                    placeholder="Classification (comma separated)"
                                                  />
                                                ) : (
                                                  <div className="flex flex-wrap gap-2">
                                                    {element.classification.length ? element.classification.map(c => (
                                                      <span key={c} className="px-3 py-1 bg-slate-100 text-slate-600 rounded-full text-xs font-bold">{c}</span>
                                                    )) : <span className="text-xs text-slate-400 italic">None</span>}
                                                  </div>
                                                )}
                                              </div>
                                            </div>
                                          </div>
                                          </div>

                                          {/* Search Metadata Card */}
                                          <div className="bg-white rounded-2xl lg:rounded-3xl border border-slate-200 shadow-sm flex flex-col overflow-hidden h-auto">
                                            <button
                                              type="button"
                                              onClick={() => toggleSubSection(element.id, "metadata")}
                                              className="flex w-full items-center justify-between p-4 lg:p-6 lg:pointer-events-none text-left"
                                            >
                                              <div className="flex flex-col gap-1">
                                                <p className="text-[10px] uppercase tracking-widest text-slate-400 font-black">Search Metadata</p>
                                                <h3 className="text-base lg:text-lg font-bold text-slate-900 leading-tight">Keywords & Tags</h3>
                                              </div>
                                              <div className="lg:hidden">
                                                {isMetadataOpen ? (
                                                  <ChevronDownIcon className="size-4 text-slate-400" />
                                                ) : (
                                                  <ChevronRightIcon className="size-4 text-slate-400" />
                                                )}
                                              </div>
                                            </button>

                                            <div className={clsx(
                                              "flex-col px-4 pb-4 lg:px-6 lg:pb-6 lg:flex",
                                              isMetadataOpen ? "flex" : "hidden"
                                            )}>
                                              <div className="flex flex-col gap-4 mb-6">
                                                <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
                                                  {!editState.isEditingMetadata && (
                                                    <button
                                                      onClick={(e) => {
                                                        e.stopPropagation();
                                                        ensureElementEditState(element, { isEditingMetadata: true, error: undefined });
                                                      }}
                                                      className="rounded-full px-4 py-1.5 text-xs font-bold border border-slate-200 text-slate-600 hover:bg-slate-50 transition-colors shrink-0"
                                                    >
                                                      Edit
                                                    </button>
                                                  )}
                                                  {editState.isEditingMetadata && (
                                                    <div className="flex flex-wrap items-center gap-2">
                                                      <button
                                                        onClick={() => handleSaveMetadata(element)}
                                                        disabled={metadataBusy}
                                                        className="rounded-full bg-primary px-4 py-1.5 text-xs font-bold text-white hover:bg-accent transition-colors disabled:opacity-50"
                                                      >
                                                        {editState.isSavingMetadata ? "..." : "Save"}
                                                      </button>
                                                      <button
                                                        onClick={() => ensureElementEditState(element, { isEditingMetadata: false })}
                                                        className="rounded-full bg-white px-4 py-1.5 text-xs font-bold text-slate-500 border border-slate-200 hover:bg-slate-50 transition-colors"
                                                      >
                                                        Cancel
                                                      </button>
                                                    </div>
                                                  )}
                                                </div>
                                              </div>

                                              <div className="space-y-6 flex-1">
                                              <div>
                                                <div className="flex items-center justify-between mb-2">
                                                  <p className="text-[10px] font-black text-slate-400 uppercase tracking-widest">Keywords</p>
                                                  {editState.isEditingMetadata && (
                                                    <button
                                                      onClick={() => handleGenerateFields(element, ["keywords"])}
                                                      disabled={metadataBusy}
                                                      className="rounded-lg bg-primary-soft px-2 py-1 text-[10px] font-bold text-primary hover:bg-primary/10 transition-colors"
                                                    >
                                                      Generate
                                                    </button>
                                                  )}
                                                </div>
                                                {editState.isEditingMetadata ? (
                                                  <textarea
                                                    value={editState.keywords}
                                                    onChange={(e) => ensureElementEditState(element, { keywords: e.target.value })}
                                                    className="w-full text-base lg:text-sm bg-slate-50 border border-slate-200 rounded-2xl p-4 focus:outline-none focus:ring-2 focus:ring-primary/20 min-h-[80px]"
                                                    placeholder="Keywords"
                                                  />
                                                ) : (
                                                  <div className="flex flex-wrap gap-2">
                                                    {element.keywords.length ? element.keywords.map(k => (
                                                      <span key={k} className="px-3 py-1 bg-slate-100 text-slate-600 rounded-full text-xs font-bold">{k}</span>
                                                    )) : <span className="text-xs text-slate-400 italic">None</span>}
                                                  </div>
                                                )}
                                              </div>

                                              <div>
                                                <div className="flex items-center justify-between mb-2">
                                                  <p className="text-[10px] font-black text-slate-400 uppercase tracking-widest">Content Tags</p>
                                                  {editState.isEditingMetadata && (
                                                    <button
                                                      onClick={() => handleGenerateFields(element, ["tags"])}
                                                      disabled={metadataBusy}
                                                      className="rounded-lg bg-primary-soft px-2 py-1 text-[10px] font-bold text-primary hover:bg-primary/10 transition-colors"
                                                    >
                                                      Generate
                                                    </button>
                                                  )}
                                                </div>
                                                {editState.isEditingMetadata ? (
                                                  <textarea
                                                    value={editState.tags}
                                                    onChange={(e) => ensureElementEditState(element, { tags: e.target.value })}
                                                    className="w-full text-base lg:text-sm bg-slate-50 border border-slate-200 rounded-2xl p-4 focus:outline-none focus:ring-2 focus:ring-primary/20 min-h-[80px]"
                                                    placeholder="Tags"
                                                  />
                                                ) : (
                                                  <div className="flex flex-wrap gap-2">
                                                    {element.tags.length ? element.tags.map(t => (
                                                      <span key={t} className="px-3 py-1 bg-slate-100 text-slate-600 rounded-full text-xs font-bold">{t}</span>
                                                    )) : <span className="text-xs text-slate-400 italic">None</span>}
                                                  </div>
                                                )}
                                              </div>
                                            </div>
                                          </div>
                                          </div>

                                          {/* Tone & Sentiment Card */}
                                          <div className="bg-white rounded-2xl lg:rounded-3xl border border-slate-200 shadow-sm overflow-hidden flex flex-col h-auto">
                                            <button
                                              type="button"
                                              onClick={() => toggleSubSection(element.id, "tone")}
                                              className="flex w-full items-center justify-between p-4 lg:p-6 lg:pointer-events-none text-left"
                                            >
                                              <div className="flex flex-col gap-1">
                                                <p className="text-[10px] uppercase tracking-widest text-slate-400 font-black">Tone & Sentiment</p>
                                                <h3 className="text-base lg:text-lg font-bold text-slate-900 leading-tight">Analysis</h3>
                                              </div>
                                              <div className="lg:hidden">
                                                {isToneOpen ? (
                                                  <ChevronDownIcon className="size-4 text-slate-400" />
                                                ) : (
                                                  <ChevronRightIcon className="size-4 text-slate-400" />
                                                )}
                                              </div>
                                            </button>

                                            <div className={clsx(
                                              "flex-col px-4 pb-4 lg:px-6 lg:pb-6 lg:flex",
                                              isToneOpen ? "flex" : "hidden"
                                            )}>
                                              <div className="flex-1">
                                                {element.sentiment ? (
                                                  <div className="inline-flex items-center rounded-2xl bg-primary-soft/50 px-6 py-3 border border-primary-soft">
                                                    <span className="text-sm font-bold text-primary">{element.sentiment.label}</span>
                                                  </div>
                                                ) : (
                                                  <p className="text-xs text-slate-400 italic">Pending analysis...</p>
                                                )}
                                              </div>
                                            </div>
                                          </div>
                                        </div>

                                        <div className="pt-4 border-t border-slate-100">
                                          <div className="flex items-center justify-between mb-4">
                                            <p className="text-[10px] uppercase tracking-wide text-slate-400 font-bold">Version history</p>
                                            <button
                                              onClick={() => toggleHistoryPanel(element.id)}
                                              className="text-[10px] font-bold text-slate-500 hover:text-black uppercase tracking-wider"
                                            >
                                              {historyIsOpen ? "Close" : "View revisions"}
                                            </button>
                                          </div>
                                          {historyIsOpen && (
                                            <div className="space-y-3">
                                              {historyBusy ? (
                                                <div className="text-center py-4"><span className="text-xs text-slate-400 animate-pulse">Loading revisions...</span></div>
                                              ) : revisionList.length ? (
                                                revisionList.map((rev, idx) => (
                                                  <div key={rev.id || idx} className="bg-slate-50/50 rounded-xl p-4 border border-slate-100 flex items-center justify-between">
                                                    <div>
                                                      <p className="text-[11px] font-bold text-slate-900">
                                                        Revision {rev.revision} · {normalizeRevisionSource(rev.source)}
                                                      </p>
                                                      <p className="text-[10px] text-slate-400 font-medium">
                                                        {rev.createdAt ? new Date(rev.createdAt).toLocaleString() : "—"}
                                                      </p>
                                                    </div>
                                                    {idx !== 0 && (
                                                      <button
                                                        onClick={() => handleRestoreRevision(element, rev)}
                                                        className="text-[10px] font-bold text-primary hover:underline uppercase"
                                                      >
                                                        Restore
                                                      </button>
                                                    )}
                                                  </div>
                                                ))
                                              ) : (
                                                <p className="text-xs text-slate-400 italic text-center py-2">No revisions yet</p>
                                              )}
                                            </div>
                                          )}
                                        </div>
                                      </div>
                                    )}
                                  </div>
                                )
                              })}
                            </div>
                          )}
                        </div>
                      )
                    })}
                  </div>
                </div>
              ) : rawSummary ? (
                <div className="bg-slate-50 rounded-2xl p-6 border border-slate-100">
                  <pre className="text-sm text-slate-700 whitespace-pre-wrap font-sans leading-relaxed">{rawSummary}</pre>
                </div>
              ) : (
                <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50/50 py-16 text-center">
                  <p className="text-sm text-slate-400 font-medium">Awaiting AI results. Refresh status to check progress.</p>
                </div>
              )}
            </section>
          </div>

          <aside className="lg:col-span-4 flex flex-col gap-8 lg:sticky lg:top-[20rem]">
            <section className="bg-white rounded-3xl border border-slate-200 p-6 shadow-sm">
               <div className="flex items-center justify-between mb-6">
                  <h2 className="text-lg font-bold">Metadata</h2>
                  <button
                    type="button"
                    onClick={() => {
                      clearEnrichmentContext();
                      router.push("/ingestion");
                    }}
                    className="text-[10px] font-bold text-primary hover:underline uppercase tracking-wider"
                  >
                    Reset
                  </button>
               </div>

               <div className="space-y-6">
                  <div>
                    <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Name</p>
                    <p className="text-sm font-bold text-gray-900 break-all">{context.metadata.name}</p>
                  </div>
                  <div>
                    <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Source</p>
                    <p className="text-sm font-bold text-gray-900">{sourceLabel}</p>
                  </div>
                  <div>
                    <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Cleansed ID</p>
                    <p className="text-sm font-bold text-gray-900 break-all">{context.metadata.cleansedId ?? "—"}</p>
                  </div>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div>
                      <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Locale</p>
                      <p className="text-sm font-bold text-gray-900 break-all">{context.metadata.locale ?? "—"}</p>
                    </div>
                    <div>
                      <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Page ID</p>
                      <p className="text-sm font-bold text-gray-900 break-all">{context.metadata.pageId ?? "—"}</p>
                    </div>
                  </div>
                  <div>
                    <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Started At</p>
                    <p className="text-[11px] font-bold text-gray-900">
                      {new Date(context.startedAt).toLocaleString()}
                    </p>
                  </div>
               </div>
            </section>

            <section className="bg-white rounded-3xl border border-slate-200 p-6 shadow-sm">
              <div className="mb-6">
                <p className="text-xs uppercase tracking-wide text-gray-400 font-bold">Metrics</p>
                <h2 className="text-lg font-bold text-gray-900">Enrichment stats</h2>
              </div>
              <div className="grid gap-4">
                <div className="bg-slate-50 rounded-2xl p-4 border border-slate-100">
                  <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Fields tagged</p>
                  <p className="text-xl font-bold text-black">{totalFieldsTagged ?? "0"}</p>
                </div>
                <div className="bg-slate-50 rounded-2xl p-4 border border-slate-100">
                  <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Readability gain</p>
                  <p className="text-xl font-bold text-black">{readabilityDisplay ?? "—"}</p>
                </div>
                <div className="bg-slate-50 rounded-2xl p-4 border border-slate-100">
                  <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Errors found</p>
                  <p className="text-xl font-bold text-black">{errorsDisplay ?? "0"}</p>
                </div>
              </div>
            </section>

            <section className="bg-white rounded-3xl border border-slate-200 p-6 shadow-sm">
              <div className="mb-6">
                <p className="text-xs uppercase tracking-wide text-gray-400 font-bold">Status timeline</p>
                <h2 className="text-lg font-bold text-gray-900">Events</h2>
              </div>
              <div className="space-y-6 relative before:absolute before:left-[5px] before:top-2 before:bottom-2 before:w-[1px] before:bg-slate-100">
                {statusHistory.map((entry, idx) => {
                  const meta = STATUS_COLORS[entry.status] ?? { dot: "bg-slate-300", className: "text-slate-400" };
                  return (
                    <div key={`${entry.status}-${idx}`} className="relative pl-6">
                      <span className={`absolute left-0 top-1.5 h-2.5 w-2.5 rounded-full ring-4 ring-white ${meta.dot}`} />
                      <p className={`text-[11px] font-bold uppercase tracking-wider ${meta.className}`}>{STATUS_LABELS[entry.status] ?? entry.status}</p>
                      <p className="text-[10px] font-medium text-slate-400">{new Date(entry.timestamp).toLocaleString()}</p>
                    </div>
                  );
                })}
              </div>
            </section>

            <section className="bg-white rounded-3xl border border-slate-200 p-6 shadow-sm">
              <div className="mb-6 text-center sm:text-left">
                <p className="text-xs uppercase tracking-wide text-gray-400 font-bold">Finalize</p>
                <h2 className="text-lg font-bold text-gray-900">Session complete?</h2>
              </div>
              <div className="flex flex-col gap-3">
                <button
                  type="button"
                  onClick={() => {
                    clearEnrichmentContext();
                    router.push("/ingestion");
                  }}
                  className="rounded-full bg-primary py-3 text-sm font-bold text-white shadow-lg hover:bg-accent transition-all text-center flex items-center justify-center"
                >
                  Finish Session
                </button>
                <button
                  type="button"
                  onClick={() => router.push("/cleansing")}
                  className="text-xs font-bold text-gray-500 hover:text-black transition-colors py-2 text-center"
                >
                  Back to Cleansing
                </button>
              </div>
            </section>
          </aside>
        </main>
      </div>
    </PipelineShell>
  );
}
