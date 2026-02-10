"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import {
  clearCleansedContext,
  loadCleansedContext,
  saveEnrichmentContext,
  type CleansedContext,
} from "@/lib/extraction-context";
import { PipelineShell } from "@/components/PipelineShell";
import { StageHero } from "@/components/StageHero";
import { pickLocale, pickPageId } from "@/lib/metadata";
import { describeSourceLabel, inferSourceType, pickString } from "@/lib/source";
import clsx from 'clsx';

const RULES = [
  {
    title: "Whitespace normalization",
    description: "Collapses redundant spaces, tabs, and line breaks to a single space.",
  },
  {
    title: "Markup removal",
    description: "Strips internal tokens (e.g. {%url%}, sosumi, wj markers) from copy blocks.",
  },
  {
    title: "Locale-aware punctuation",
    description: "Replaces smart quotes, ellipsis, and em-dashes with locale-specific glyphs.",
  },
  {
    title: "Sensitive token scrub",
    description: "Masks e-mail addresses, PII placeholders, and debugging metadata.",
  },
];

type Feedback = {
  state: "idle" | "loading" | "success" | "error";
  message?: string;
};

type PreviewRow = {
  id: string;
  field: string;
  original?: string | null;
  cleansed?: string | null;
};

type RemoteCleansedContext = {
  metadata: CleansedContext["metadata"];
  status?: string;
  rawBody?: string;
  fallbackReason?: string;
  cachedItems?: PreviewRow[];
};

const mapLocalContext = (local: CleansedContext | null): RemoteCleansedContext | null => {
  if (!local) return null;
  return {
    metadata: local.metadata,
    status: local.status,
    rawBody: local.rawBody,
    fallbackReason: local.fallbackReason,
    cachedItems: normalizeStoredItems(local.items),
  };
};

const normalizeStoredItems = (items?: unknown[]): PreviewRow[] => {
  if (!Array.isArray(items)) return [];
  return items.reduce<PreviewRow[]>((rows, item, index) => {
    if (!item || typeof item !== "object") {
      return rows;
    }
    const record = item as Record<string, unknown>;
    const field = pickString(record.field);
    const original = pickString(record.original);
    const cleansed = pickString(record.cleansed);
    if (!field && !original && !cleansed) {
      return rows;
    }
    rows.push({
      id: pickString(record.id) ?? `cached-${index}`,
      field: field ?? `Item ${index + 1}`,
      original: original ?? null,
      cleansed: cleansed ?? null,
    });
    return rows;
  }, []);
};

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

const pickNumber = (value: unknown) => {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  return undefined;
};

const buildDefaultMetadata = (
  id: string,
  fallback?: CleansedContext["metadata"],
): CleansedContext["metadata"] => {
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
  fallback: CleansedContext["metadata"],
  id: string,
): CleansedContext["metadata"] => {
  if (!backend) return fallback;
  const metadataRecord =
    backend.metadata && typeof backend.metadata === "object"
      ? (backend.metadata as Record<string, unknown>)
      : null;

  const next: CleansedContext["metadata"] = { ...fallback };

  if (metadataRecord) {
    next.name = pickString(metadataRecord.name) ?? next.name;
    next.source = pickString(metadataRecord.source) ?? next.source;
    next.cleansedId = pickString(metadataRecord.cleansedId) ?? next.cleansedId;
    next.status = pickString(metadataRecord.status) ?? next.status;
    next.sourceIdentifier =
      pickString(metadataRecord.sourceIdentifier) ?? next.sourceIdentifier;
    next.sourceType = pickString(metadataRecord.sourceType) ?? next.sourceType;
    next.locale = pickLocale(metadataRecord) ?? next.locale;
    next.pageId = pickPageId(metadataRecord) ?? next.pageId;
    const uploadedAtCandidate = pickNumber(metadataRecord.uploadedAt);
    if (uploadedAtCandidate) {
      next.uploadedAt = uploadedAtCandidate;
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
  next.locale = pickLocale(backend) ?? next.locale;
  next.pageId = pickPageId(backend) ?? next.pageId;
  next.cleansedId =
    pickString(backend.cleansedId) ??
    pickString(backend.cleansedDataStoreId) ??
    next.cleansedId ??
    id;

  return next;
};

const FeedbackPill = ({ feedback }: { feedback: Feedback }) => {
  if (feedback.state === "idle") return null;
  const base =
    feedback.state === "loading"
      ? "bg-slate-50 text-slate-600"
      : feedback.state === "success"
        ? "bg-slate-100 text-slate-900 border border-slate-200"
        : "bg-slate-50 text-slate-500 border border-slate-200";

  return (
    <div className={`inline-flex items-center gap-2 rounded-full px-4 py-1.5 text-[10px] font-bold uppercase tracking-wider ${base}`}>
      {feedback.state === "loading" ? (
        <>
          <span className="size-1.5 rounded-full bg-slate-400 animate-pulse" />
          Triggering enrichment…
        </>
      ) : (
        <>
          <span className={clsx("size-1.5 rounded-full", feedback.state === "success" ? "bg-black" : "bg-slate-300")} />
          {feedback.message ?? (feedback.state === "success" ? "Enrichment triggered." : "Something went wrong.")}
        </>
      )}
    </div>
  );
};

export default function CleansingPageClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const queryId = searchParams.get("id");
  const localSnapshot = mapLocalContext(loadCleansedContext());

  const [context, setContext] = useState<RemoteCleansedContext | null>(localSnapshot);
  const [items, setItems] = useState<PreviewRow[]>(localSnapshot?.cachedItems ?? []);
  const [loading, setLoading] = useState<boolean>(!localSnapshot);
  const [error, setError] = useState<string | null>(null);
  const [enrichmentFeedback, setEnrichmentFeedback] = useState<Feedback>({ state: "idle" });
  const [itemsLoading, setItemsLoading] = useState(false);
  const [itemsError, setItemsError] = useState<string | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    setHydrated(true);
  }, []);

  useEffect(() => {
    const fallbackId = localSnapshot?.metadata.cleansedId ?? null;
    setActiveId(queryId ?? fallbackId);
  }, [queryId, localSnapshot?.metadata.cleansedId]);

  const fetchItems = async (id: string, options: { showSpinner?: boolean } = {}) => {
    const { showSpinner = true } = options;
    if (showSpinner) {
      setItemsLoading(true);
      setItems([]);
    }
    setItemsError(null);
    try {
      const response = await fetch(`/api/ingestion/cleansed-items?id=${encodeURIComponent(id)}`);
      const { body, rawBody } = await parseJson(response);
      if (!response.ok) {
        if (response.status === 404) {
          setItems([]);
          setItemsError("Cleansed rows are not available yet.");
          return;
        }
        throw new Error(
          (body as Record<string, unknown>)?.error as string ??
            rawBody ??
            "Backend rejected the items request.",
        );
      }
      const payloadRecord = (body as Record<string, unknown>) ?? {};
      const normalized = Array.isArray(payloadRecord.items)
        ? (payloadRecord.items as PreviewRow[])
        : [];
      setItems(normalized);
      setContext((previous) =>
        previous
          ? {
              ...previous,
              cachedItems: normalized,
              rawBody:
                typeof (body as Record<string, unknown>)?.rawBody === "string"
                  ? ((body as Record<string, unknown>).rawBody as string)
                  : previous.rawBody,
            }
          : previous,
      );
    } catch (itemsErr) {
      setItemsError(itemsErr instanceof Error ? itemsErr.message : "Unable to fetch cleansed items.");
    } finally {
      if (showSpinner) {
        setItemsLoading(false);
      }
    }
  };

  useEffect(() => {
    const fetchContext = async (id: string | null) => {
      if (!id) {
        setLoading(false);
        setError("Provide a cleansed ID via the URL or trigger a new run.");
        setContext(localSnapshot);
        setItems(localSnapshot?.cachedItems ?? []);
        return;
      }
      setLoading(true);
      setError(null);
      try {
        const response = await fetch(`/api/ingestion/cleansed-context?id=${encodeURIComponent(id)}`);
        const { body, rawBody } = await parseJson(response);
        if (!response.ok) {
          throw new Error(
            (body as Record<string, unknown>)?.error as string ??
              rawBody ??
              "Backend rejected the cleansed context request.",
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
        const remoteMetadata = buildMetadataFromBackend(backendRecord, fallbackMetadata, id);
        const proxiedRawBody =
          pickString(proxyPayload.rawBody) ?? (typeof rawBody === "string" ? rawBody : undefined);
        const remoteContext: RemoteCleansedContext = {
          metadata: remoteMetadata,
          status: pickString(backendRecord?.status) ?? localSnapshot?.status,
          rawBody: proxiedRawBody,
          fallbackReason:
            pickString(proxyPayload.fallbackReason) ??
            pickString(backendRecord?.fallbackReason) ??
            localSnapshot?.fallbackReason,
        };
        setContext(remoteContext);
        if (remoteContext.cachedItems?.length) {
          setItems(remoteContext.cachedItems);
        }
        await fetchItems(id, { showSpinner: !(remoteContext.cachedItems?.length) });
      } catch (contextError) {
        setError(
          contextError instanceof Error ? contextError.message : "Unable to load cleansed context.",
        );
        if (localSnapshot) {
          setContext(localSnapshot);
          setItems(localSnapshot.cachedItems ?? []);
        } else {
          setContext(null);
          setItems([]);
        }
      } finally {
        setLoading(false);
      }
    };

    fetchContext(activeId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId]);

  const handleSendToEnrichment = async () => {
    if (!context?.metadata.cleansedId) {
      setEnrichmentFeedback({
        state: "error",
        message: "Cleansed ID is missing. Re-run extraction before enrichment.",
      });
      return;
    }

    setEnrichmentFeedback({ state: "loading", message: "Triggering enrichment…" });
    try {
      const response = await fetch("/api/ingestion/enrichment", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id: context.metadata.cleansedId }),
      });
      const payload = await response.json();

      if (!response.ok) {
        setEnrichmentFeedback({
          state: "error",
          message: payload?.error ?? "Backend rejected the request.",
        });
        return;
      }

      const now = Date.now();
      saveEnrichmentContext({
        metadata: context.metadata,
        startedAt: now,
        statusHistory: [
          { status: "ENRICHMENT_TRIGGERED", timestamp: now },
          {
            status:
              typeof payload?.body?.status === "string"
                ? payload.body.status
                : "WAITING_FOR_RESULTS",
            timestamp: now,
          },
        ],
      });

      setEnrichmentFeedback({
        state: "success",
        message: "Enrichment pipeline triggered.",
      });
      router.push(`/enrichment?id=${encodeURIComponent(context.metadata.cleansedId)}`);
    } catch (error) {
      setEnrichmentFeedback({
        state: "error",
        message:
          error instanceof Error ? error.message : "Unable to reach enrichment service.",
      });
    }
  };

  if (loading || !hydrated) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 px-6 py-16">
        <div className="max-w-lg rounded-3xl border border-slate-200 bg-white p-10 text-center shadow-sm">
          <p className="text-xs uppercase tracking-wide text-slate-400">Cleansing</p>
          <h1 className="mt-2 text-2xl font-semibold text-slate-900">Loading context…</h1>
          <p className="mt-3 text-sm text-slate-500">
            Fetching cleansed snapshot from the backend. One moment please.
          </p>
        </div>
      </div>
    );
  }

  if (!context) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 px-6 py-16">
        <div className="max-w-lg rounded-3xl border border-slate-200 bg-white p-10 text-center shadow-sm">
          <p className="text-xs uppercase tracking-wide text-slate-400">Cleansing</p>
          <h1 className="mt-2 text-2xl font-semibold text-slate-900">
            {error ?? "Cleansed context not found"}
          </h1>
          <p className="mt-3 text-sm text-slate-500">
            Provide a valid `id` query parameter or trigger the pipeline again.
          </p>
          <button
            type="button"
            onClick={() => router.push("/extraction")}
            className="mt-6 rounded-full bg-slate-900 px-6 py-2 text-sm font-semibold text-white"
          >
            Back to Extraction
          </button>
        </div>
      </div>
    );
  }

  const sourceLabel = describeSourceLabel(
    context.metadata.sourceType ?? context.metadata.source,
    context.metadata.source,
  );
  const sourceIdentifier = context.metadata.sourceIdentifier ?? "—";

  return (
    <PipelineShell currentStep="cleansing" breadcrumbExtra={context.metadata.name}>
      <StageHero
        title="Cleansing"
        description={`Review cleansed output for ${context.metadata.name} before sending it forward.`}
        actionsSlot={<FeedbackPill feedback={enrichmentFeedback} />}
      />

      <main className="mx-auto flex max-w-[1600px] flex-col gap-6 px-8 py-8">
        <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
          {/* Left Pane: Items Table */}
          <section className="flex flex-col rounded-3xl border border-slate-200 bg-white shadow-sm overflow-hidden">
            <div className="p-6 border-b border-slate-100 flex items-center justify-between">
              <div>
                <h3 className="text-sm font-bold uppercase tracking-widest text-slate-400">
                  Cleansed Items
                </h3>
                <p className="mt-1 text-xs font-medium text-slate-500">
                  {items.length} fields processed
                </p>
              </div>
            </div>

            <div className="flex-1 overflow-auto scrollbar-thin scrollbar-thumb-slate-200 scrollbar-track-transparent">
              {itemsLoading ? (
                <div className="py-20 text-center">
                  <span className="inline-flex size-6 animate-spin rounded-full border-2 border-slate-200 border-t-black" />
                  <p className="mt-4 text-sm font-medium text-slate-500">Loading cleansed data...</p>
                </div>
              ) : itemsError ? (
                <div className="p-10 text-center">
                  <p className="text-sm font-semibold text-slate-900">{itemsError}</p>
                  <button
                    type="button"
                    onClick={() => context.metadata.cleansedId && fetchItems(context.metadata.cleansedId)}
                    className="mt-4 rounded-xl bg-black px-6 py-2 text-xs font-bold text-white uppercase tracking-widest"
                  >
                    Retry fetch
                  </button>
                </div>
              ) : items.length === 0 ? (
                <div className="py-20 text-center opacity-40">
                  <p className="text-sm font-medium text-slate-500">No items available.</p>
                </div>
              ) : (
                <table className="w-full text-left text-sm">
                  <thead className="sticky top-0 bg-slate-50 text-[10px] font-bold uppercase tracking-widest text-slate-400 z-10">
                    <tr>
                      <th className="px-6 py-4">Field</th>
                      <th className="px-6 py-4">Original</th>
                      <th className="px-6 py-4">Cleansed</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100 bg-white">
                    {items.map((row, index) => (
                      <tr key={row.id ?? `${row.field ?? "row"}-${index}`} className="group hover:bg-slate-50/50 transition-colors">
                        <td className="px-6 py-4 align-top font-bold text-black min-w-[140px]">
                          {row.field}
                        </td>
                        <td className="px-6 py-4 align-top">
                          <div className="max-h-32 overflow-y-auto text-xs text-slate-400 leading-relaxed scrollbar-thin">
                            {row.original ?? "—"}
                          </div>
                        </td>
                        <td className="px-6 py-4 align-top">
                          <div className="max-h-32 overflow-y-auto text-xs text-slate-900 font-medium leading-relaxed scrollbar-thin">
                            {row.cleansed ?? "—"}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            <div className="p-6 bg-slate-50 border-t border-slate-100 flex items-center justify-between">
              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={() => router.push("/extraction")}
                  className="rounded-xl border border-slate-200 bg-white px-6 py-3 text-xs font-bold uppercase tracking-widest text-slate-500 hover:border-black hover:text-black transition-all"
                >
                  Back
                </button>
                <button
                  type="button"
                  onClick={() => {
                    clearCleansedContext();
                    router.push("/ingestion");
                  }}
                  className="rounded-xl border border-slate-200 bg-white px-6 py-3 text-xs font-bold uppercase tracking-widest text-slate-500 hover:border-black hover:text-black transition-all"
                >
                  Reset
                </button>
              </div>
              <button
                type="button"
                onClick={handleSendToEnrichment}
                disabled={enrichmentFeedback.state === "loading"}
                className="rounded-xl bg-black px-10 py-3 text-xs font-bold uppercase tracking-widest text-white shadow-lg shadow-black/20 transition-all hover:bg-slate-800"
              >
                {enrichmentFeedback.state === "loading" ? "Processing..." : "Send to Enrichment"}
              </button>
            </div>
          </section>

          {/* Right Pane: Metadata & Rules */}
          <aside className="space-y-6">
            <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
              <h3 className="text-[10px] font-bold uppercase tracking-widest text-slate-400 mb-6">
                File Metadata
              </h3>
              <div className="space-y-6">
                {[
                  { label: "Status", value: context.status ?? "CLEANSED" },
                  { label: "Locale", value: context.metadata.locale ?? "—" },
                  { label: "Page ID", value: context.metadata.pageId ?? "—" },
                  { label: "Source", value: sourceLabel },
                  { label: "Cleansed ID", value: context.metadata.cleansedId ?? "—" },
                ].map((item) => (
                  <div key={item.label}>
                    <dt className="text-[10px] font-bold uppercase tracking-widest text-slate-400">
                      {item.label}
                    </dt>
                    <dd className="mt-1 text-sm font-bold text-black break-all">{item.value}</dd>
                  </div>
                ))}
              </div>
            </section>

            <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
              <h3 className="text-[10px] font-bold uppercase tracking-widest text-slate-400 mb-4">
                Applied Heuristics
              </h3>
              <div className="space-y-4">
                {RULES.map((rule) => (
                  <div key={rule.title} className="group">
                    <p className="text-xs font-bold text-black group-hover:text-slate-500 transition-colors">
                      {rule.title}
                    </p>
                    <p className="mt-1 text-[10px] text-slate-400 leading-normal">
                      {rule.description}
                    </p>
                  </div>
                ))}
              </div>
            </section>
          </aside>
        </div>
      </main>
    </PipelineShell>
  );
  }