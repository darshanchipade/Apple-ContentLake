"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import {
  clearCleansedContext,
  loadCleansedContext,
  saveEnrichmentContext,
  type CleansedContext,
} from "@/lib/extraction-context";
import { PipelineShell } from "@/components/PipelineShell";
import { pickLocale, pickPageId } from "@/lib/metadata";
import { describeSourceLabel, inferSourceType, pickString } from "@/lib/source";
import { formatBytes } from "../../lib/format";

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
      ? "bg-primary-soft text-primary"
      : feedback.state === "success"
        ? "bg-primary-soft text-primary"
        : "bg-rose-50 text-rose-700";

  return (
    <div className={`inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold ${base}`}>
      {feedback.state === "loading"
        ? "Triggering enrichment…"
        : feedback.message ?? (feedback.state === "success" ? "Enrichment triggered." : "Something went wrong.")}
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
      const initialStatus =
        typeof payload?.body?.status === "string" ? payload.body.status : "WAITING_FOR_RESULTS";

      const statusHistory =
        initialStatus === "ENRICHED_NO_ITEMS_TO_PROCESS"
          ? [{ status: initialStatus, timestamp: now }]
          : [
              { status: "ENRICHMENT_TRIGGERED", timestamp: now },
              { status: initialStatus, timestamp: now },
            ];

      saveEnrichmentContext({
        metadata: context.metadata,
        startedAt: now,
        statusHistory,
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
      <PipelineShell currentStep="cleansing" showTracker={false}>
        <div className="flex h-[calc(100vh-64px)] items-center justify-center bg-gray-50/50 p-8">
          <div className="max-w-md w-full rounded-2xl border border-gray-200 bg-white p-10 text-center shadow-sm">
            <h2 className="text-xl font-bold text-gray-900">{error ?? "Cleansed context not found"}</h2>
            <p className="mt-4 text-sm text-gray-500">
              Provide a valid `id` query parameter or trigger the pipeline again.
            </p>
            <button
              type="button"
              onClick={() => router.push("/extraction")}
              className="mt-8 btn-primary w-full"
            >
              Back to Extraction
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
    <PipelineShell currentStep="cleansing">
      <div className="min-h-[calc(100vh-4rem)] bg-background">
        <section className="border-b border-slate-200 bg-white">
          <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-3 px-4 py-4 sm:px-6 sm:py-6 lg:px-8">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between text-left">
              <div className="space-y-1 sm:space-y-2">
                <h1 className="text-2xl sm:text-3xl font-bold text-black">Cleansing</h1>
                <p className="text-xs sm:text-sm font-medium text-slate-500 lg:max-w-2xl">
                  Review cleansed output for {context.metadata.name} before sending it forward.
                </p>
              </div>
              <div className="flex justify-center lg:justify-end">
                <FeedbackPill feedback={enrichmentFeedback} />
              </div>
            </div>
          </div>
        </section>

        <main className="mx-auto grid max-w-[1600px] gap-6 px-4 py-6 sm:px-6 sm:py-8 lg:grid-cols-12 items-stretch">
          <div className="lg:col-span-8 flex flex-col gap-8">
            <section className="bg-white rounded-3xl border border-slate-200 p-4 lg:p-8 shadow-sm flex flex-col lg:h-[calc(100vh-22rem)] lg:min-h-[600px]">
              <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between mb-6">
                <div>
                  <p className="text-xs uppercase tracking-wide text-gray-400">Items</p>
                  <h2 className="text-lg font-bold text-gray-900">
                    Original vs Cleansed values
                  </h2>
                </div>
              </div>

              <div className="flex-1 min-h-0 flex flex-col">
              {itemsLoading ? (
                <div className="rounded-2xl border border-gray-100 bg-gray-50 py-10 text-center text-sm text-gray-400">
                  Fetching latest cleansed rows…
                </div>
              ) : itemsError ? (
                <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
                  <p className="font-semibold">Unable to load cleansed items.</p>
                  <p className="mt-1">{itemsError}</p>
                  <button
                    type="button"
                    onClick={() => context.metadata.cleansedId && fetchItems(context.metadata.cleansedId)}
                    className="mt-3 rounded-full bg-amber-600 px-3 py-1 text-xs font-semibold text-white"
                  >
                    Retry fetch
                  </button>
                </div>
              ) : items.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-gray-200 py-10 text-center text-sm text-gray-400">
                  No cleansed items available yet.
                </div>
              ) : (
                <div className="rounded-2xl border border-slate-100 overflow-hidden w-full flex-1 flex flex-col min-h-0">
                  <div className="flex-1 overflow-y-auto custom-scrollbar">
                    {/* Desktop Table */}
                    <table className="hidden lg:table w-full text-left text-sm">
                      <thead className="bg-slate-50 text-[10px] uppercase tracking-widest text-slate-400 sticky top-0 z-10">
                        <tr>
                          <th className="px-6 py-4 font-black">Field</th>
                          <th className="px-6 py-4 font-black">Original</th>
                          <th className="px-6 py-4 font-black">Cleansed</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100 bg-white">
                        {items.map((row, index) => (
                          <tr key={row.id ?? `${row.field ?? "row"}-${index}`} className="hover:bg-slate-50/50 transition-colors">
                            <td className="px-6 py-4 align-top font-bold text-slate-900 w-1/4">
                              {row.field}
                            </td>
                            <td className="px-6 py-4 align-top text-slate-500 w-3/8">
                              <pre className="whitespace-pre-wrap text-[11px] font-sans leading-relaxed">
                                {row.original ?? "—"}
                              </pre>
                            </td>
                            <td className="px-6 py-4 align-top text-slate-900 font-bold w-3/8">
                              <pre className="whitespace-pre-wrap text-[11px] font-sans leading-relaxed">
                                {row.cleansed ?? "—"}
                              </pre>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>

                    {/* Mobile Card View */}
                    <div className="lg:hidden divide-y divide-slate-100">
                      {items.map((row, index) => (
                        <div key={row.id ?? `${row.field ?? "row"}-${index}`} className="p-4 space-y-4">
                          <div className="flex items-center justify-between">
                            <span className="text-[10px] font-black text-primary uppercase tracking-widest">
                              {row.field}
                            </span>
                            <span className="text-[10px] font-bold text-slate-300">#{index + 1}</span>
                          </div>
                          <div className="grid gap-4">
                            <div className="bg-slate-50 rounded-xl p-3 border border-slate-100">
                              <p className="text-[9px] font-bold text-slate-400 uppercase tracking-widest mb-2">Original</p>
                              <p className="text-xs text-slate-500 leading-relaxed break-all">{row.original ?? "—"}</p>
                            </div>
                            <div className="bg-primary-soft/30 rounded-xl p-3 border border-primary-soft">
                              <p className="text-[9px] font-bold text-primary uppercase tracking-widest mb-2">Cleansed</p>
                              <p className="text-xs text-slate-900 font-bold leading-relaxed break-all">{row.cleansed ?? "—"}</p>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              )}
              </div>
            </section>
          </div>

          <aside className="lg:col-span-4 flex flex-col gap-8 lg:sticky lg:top-[20rem]">
            <section className="bg-white rounded-3xl border border-slate-200 p-6 shadow-sm shrink-0">
               <div className="flex items-center justify-between mb-6">
                  <h2 className="text-lg font-bold">Metadata</h2>
                  <button
                    type="button"
                    onClick={() => {
                      clearCleansedContext();
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
                    <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Status</p>
                    <p className="text-sm font-bold text-gray-900">{context.status ?? "Pending"}</p>
                  </div>
                  <div className="grid grid-cols-1 min-[400px]:grid-cols-2 gap-4">
                    <div>
                      <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Size</p>
                      <p className="text-sm font-bold text-gray-900">{formatBytes(context.metadata.size)}</p>
                    </div>
                    <div>
                      <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Locale</p>
                      <p className="text-sm font-bold text-gray-900">{context.metadata.locale ?? "—"}</p>
                    </div>
                  </div>
                  <div>
                    <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Uploaded</p>
                    <p className="text-[11px] font-bold text-gray-900">
                      {new Date(context.metadata.uploadedAt).toLocaleString()}
                    </p>
                  </div>
               </div>
            </section>

            <section className="bg-white rounded-3xl border border-slate-200 p-6 shadow-sm">
              <div className="mb-4">
                <p className="text-xs uppercase tracking-wide text-gray-400 font-bold">Applied rules</p>
                <h2 className="text-lg font-bold text-gray-900">Heuristics</h2>
              </div>
              <div className="space-y-3">
                {RULES.map((rule) => (
                  <div
                    key={rule.title}
                    className="rounded-xl border border-gray-100 bg-gray-50/50 p-3"
                  >
                    <p className="text-xs font-bold text-gray-900">{rule.title}</p>
                    <p className="mt-1 text-[10px] text-gray-500 leading-relaxed">{rule.description}</p>
                  </div>
                ))}
              </div>
            </section>

            <section className="bg-white rounded-3xl border border-slate-200 p-6 shadow-sm">
              <div className="mb-6 text-center sm:text-left">
                <p className="text-xs uppercase tracking-wide text-gray-400 font-bold">Finalize</p>
                <h2 className="text-lg font-bold text-gray-900">Continue?</h2>
              </div>
              <div className="flex flex-col gap-3">
                <button
                  type="button"
                  onClick={handleSendToEnrichment}
                  disabled={enrichmentFeedback.state === "loading"}
                  className="rounded-full bg-primary py-3 text-sm font-semibold text-white transition hover:bg-accent disabled:opacity-50 w-full text-center flex items-center justify-center shadow-lg"
                >
                  {enrichmentFeedback.state === "loading"
                    ? "Sending..."
                    : "Send to Enrichment"}
                </button>
                <button
                  type="button"
                  onClick={() => router.push("/extraction")}
                  className="text-xs font-bold text-gray-500 hover:text-gray-900 transition-colors py-2 text-center"
                >
                  Back to Extraction
                </button>
              </div>
            </section>
          </aside>
        </main>
      </div>
    </PipelineShell>
  );
}
