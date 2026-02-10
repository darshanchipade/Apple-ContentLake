"use client";

import {
  ArrowPathIcon,
  CheckCircleIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  ExclamationCircleIcon,
  InboxStackIcon,
  MagnifyingGlassIcon,
} from "@heroicons/react/24/outline";
import clsx from "clsx";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  TreeNode,
  buildTreeFromJson,
  filterTree,
} from "@/lib/tree";
import {
  ExtractionContext,
  clearExtractionContext,
  loadExtractionContext,
  saveCleansedContext,
  type PersistenceResult,
} from "@/lib/extraction-context";
import type { ExtractionSnapshot } from "@/lib/extraction-snapshot";
import { readClientSnapshot } from "@/lib/client/snapshot-store";
import { PipelineShell } from "@/components/PipelineShell";
import { StageHero } from "@/components/StageHero";
import { describeSourceLabel, inferSourceType, pickString } from "@/lib/source";

const formatBytes = (bytes: number) => {
  if (!Number.isFinite(bytes)) return "—";
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.floor(Math.log(bytes) / Math.log(1024));
  const value = bytes / Math.pow(1024, index);
  return `${value.toFixed(value > 9 || index === 0 ? 0 : 1)} ${units[index]}`;
};

const safeJsonParse = (value: string | undefined) => {
  if (!value) return null;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
};

type Feedback = {
  state: "idle" | "loading" | "success" | "error";
  message?: string;
};

const FeedbackPill = ({ feedback }: { feedback: Feedback }) => {
  if (feedback.state === "idle") return null;
  const className = clsx(
    "inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold",
    feedback.state === "success"
      ? "bg-emerald-50 text-emerald-700"
      : feedback.state === "error"
        ? "bg-rose-50 text-rose-700"
        : "bg-indigo-50 text-indigo-600",
  );
  const Icon =
    feedback.state === "loading"
      ? ArrowPathIcon
      : feedback.state === "success"
        ? CheckCircleIcon
        : ExclamationCircleIcon;
  const message =
    feedback.message ??
    (feedback.state === "loading"
      ? "Contacting backend..."
      : feedback.state === "success"
        ? "Completed successfully."
        : "Something went wrong.");
  return (
    <div className={className}>
      <Icon className={clsx("size-4", feedback.state === "loading" && "animate-spin")} />
      {message}
    </div>
  );
};

const getValueAtPath = (payload: any, path: string) => {
  if (!payload) return undefined;
  const segments = path.split(".");
  let current: any = payload;
  for (const segment of segments) {
    if (!segment) continue;
    if (segment.startsWith("[")) {
      const index = Number(segment.replace(/[^0-9]/g, ""));
      if (!Array.isArray(current) || Number.isNaN(index)) {
        return undefined;
      }
      current = current[index];
    } else if (current && typeof current === "object") {
      current = current[segment];
    } else {
      return undefined;
    }
  }
  return current;
};

const flattenTree = (nodes: TreeNode[]) => {
  const map = new Map<string, TreeNode>();
  const traverse = (node: TreeNode) => {
    map.set(node.id, node);
    node.children?.forEach(traverse);
  };
  nodes.forEach(traverse);
  return map;
};

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === "object" && value !== null;
};

const extractItemsFromBackend = (payload: unknown): unknown[] => {
  if (Array.isArray(payload)) return payload;
  if (isRecord(payload)) {
    const candidates = [
      payload.items,
      payload.records,
      payload.data,
      payload.payload,
      payload.cleansedItems,
      payload.originalItems,
      payload.result,
      payload.body,
      payload.cleansedItems,
      payload.originalItems,
      payload.result,
      payload.body,
    ];

    for (const candidate of candidates) {
      if (Array.isArray(candidate)) {
        return candidate;
      }
      if (candidate && typeof candidate === "object") {
        const record = candidate as Record<string, unknown>;
        if (Array.isArray(record.items)) {
          return record.items as unknown[];
        }
      }
    }
  }
  return [];
};

const extractStatusFromBackend = (payload: unknown): string | undefined => {
  if (!isRecord(payload)) return undefined;
  const candidates = [
    payload.status,
    payload.state,
    payload.currentStatus,
    payload.pipelineStatus,
  ];
  return candidates.find((value) => typeof value === "string") as string | undefined;
};

const buildCleansedContextPayload = (
  metadata: ExtractionContext["metadata"],
  backendResponse: any,
) => {
  const body = backendResponse?.body ?? backendResponse;
  const bodyRecord =
    body && typeof body === "object" && !Array.isArray(body)
      ? (body as Record<string, unknown>)
      : null;
  const metadataRecord =
    bodyRecord?.metadata && typeof bodyRecord.metadata === "object"
      ? (bodyRecord.metadata as Record<string, unknown>)
      : null;
  const sourceIdentifier =
    pickString(bodyRecord?.sourceIdentifier) ??
    pickString(bodyRecord?.sourceUri) ??
    pickString(metadataRecord?.sourceIdentifier) ??
    metadata.sourceIdentifier;
  const sourceType =
    inferSourceType(
      pickString(bodyRecord?.sourceType) ?? pickString(metadataRecord?.sourceType),
      sourceIdentifier ?? metadata.sourceIdentifier,
      metadata.sourceType,
    ) ?? metadata.sourceType;
  const cleansedId =
    pickString(bodyRecord?.cleansedDataStoreId) ??
    pickString(bodyRecord?.cleansedId) ??
    metadata.cleansedId;
  const mergedMetadata: ExtractionContext["metadata"] = {
    ...metadata,
    sourceIdentifier: sourceIdentifier ?? metadata.sourceIdentifier,
    sourceType: sourceType ?? metadata.sourceType,
    source: describeSourceLabel(sourceType ?? metadata.sourceType, metadata.source),
    cleansedId: cleansedId ?? metadata.cleansedId,
  };
  return {
    metadata: mergedMetadata,
    items: extractItemsFromBackend(body),
    rawBody: typeof backendResponse?.rawBody === "string" ? backendResponse.rawBody : undefined,
    status: extractStatusFromBackend(body) ?? extractStatusFromBackend(backendResponse),
  };
};

const composeSuccessMessage = (storageResult?: PersistenceResult) => {
  if (!storageResult) {
    return "Cleansing pipeline triggered.";
  }
  if (!storageResult.ok) {
    return "Cleansing pipeline triggered, but preview caching failed.";
  }
  if (storageResult.usedFallback) {
    return "Cleansing pipeline triggered. Preview cached partially because the payload is large.";
  }
  return "Cleansing pipeline triggered.";
};

export default function ExtractionPage() {
  const router = useRouter();
  const [hydrated, setHydrated] = useState(false);
  const [context, setContext] = useState<ExtractionContext | null>(null);
  const [parsedJson, setParsedJson] = useState<any>(null);
  const [treeNodes, setTreeNodes] = useState<TreeNode[]>([]);
  const [expandedNodes, setExpandedNodes] = useState<Set<string>>(new Set());
  const [activeNodeId, setActiveNodeId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [feedback, setFeedback] = useState<Feedback>({ state: "idle" });
  const [sending, setSending] = useState(false);
  const [nodeMap, setNodeMap] = useState<Map<string, TreeNode>>(new Map());
  const [snapshot, setSnapshot] = useState<ExtractionSnapshot | null>(null);
  const [snapshotLoading, setSnapshotLoading] = useState(false);
  const [snapshotError, setSnapshotError] = useState<string | null>(null);
  const [snapshotVersion, setSnapshotVersion] = useState(0);

  useEffect(() => {
    setHydrated(true);
  }, []);

  const applyTreeFromNodes = useCallback((nodes: TreeNode[]) => {
    const flattened = flattenTree(nodes);
    setTreeNodes(nodes);
    setNodeMap(flattened);
    setExpandedNodes(new Set(nodes.map((node) => node.id)));
    setActiveNodeId((previous) => {
      if (previous && flattened.has(previous)) {
        return previous;
      }
      return nodes[0]?.id ?? null;
    });
  }, []);

  const hydrateStructure = useCallback(
    (tree?: TreeNode[], rawJson?: string) => {
      if (tree && tree.length) {
        applyTreeFromNodes(tree);
        setParsedJson(rawJson ? safeJsonParse(rawJson) : null);
        return;
      }

      if (rawJson) {
        const parsed = safeJsonParse(rawJson);
        setParsedJson(parsed);
        if (parsed) {
          const nodes = buildTreeFromJson(parsed, [], { value: 0 });
          if (nodes.length) {
            applyTreeFromNodes(nodes);
          } else {
            setTreeNodes([]);
            setNodeMap(new Map<string, TreeNode>());
            setExpandedNodes(new Set());
            setActiveNodeId(null);
          }
        } else {
          setTreeNodes([]);
          setNodeMap(new Map<string, TreeNode>());
          setExpandedNodes(new Set());
          setActiveNodeId(null);
        }
        return;
      }

      setTreeNodes([]);
      setNodeMap(new Map<string, TreeNode>());
      setExpandedNodes(new Set());
      setActiveNodeId(null);
      setParsedJson(null);
    },
    [applyTreeFromNodes],
  );

  useEffect(() => {
    const payload = loadExtractionContext();
    if (!payload) return;
    setContext(payload);
    if ((payload.tree && payload.tree.length) || payload.rawJson) {
      hydrateStructure(payload.tree, payload.rawJson);
    }
  }, [hydrateStructure]);

  useEffect(() => {
    if (!context?.snapshotId) {
      setSnapshot(null);
      setSnapshotLoading(false);
      setSnapshotError(null);
      return;
    }

    const snapshotId = context.snapshotId;
    let cancelled = false;
    const loadSnapshot = async () => {
      if (snapshotVersion === 0) {
        setSnapshot(null);
      }
      setSnapshotLoading(true);
      setSnapshotError(null);
      try {
        let snapshotPayload: ExtractionSnapshot | null = null;
        if (snapshotId.startsWith("local:")) {
          snapshotPayload = await readClientSnapshot(snapshotId);
          if (!snapshotPayload) {
            throw new Error("Local extraction snapshot not found.");
          }
        } else {
          const response = await fetch(
            `/api/ingestion/context?id=${encodeURIComponent(snapshotId)}`,
          );
          let body: any = null;
          try {
            body = await response.json();
          } catch {
            // ignore parse errors
          }
          if (!response.ok) {
            throw new Error(body?.error ?? "Failed to load extraction snapshot.");
          }
          snapshotPayload = body as ExtractionSnapshot;
        }
        if (cancelled) return;
        setSnapshot(snapshotPayload);
        hydrateStructure(snapshotPayload?.tree, snapshotPayload?.rawJson);
        setSnapshotLoading(false);
      } catch (error) {
        if (cancelled) return;
        setSnapshotError(
          error instanceof Error
            ? error.message
            : "Failed to load extraction snapshot.",
        );
        setSnapshotLoading(false);
      }
    };

    loadSnapshot();

    return () => {
      cancelled = true;
    };
  }, [context?.snapshotId, snapshotVersion, hydrateStructure]);

  const retrySnapshotFetch = () => {
    if (context?.snapshotId) {
      setSnapshotVersion((value) => value + 1);
    }
  };

  const filteredTree = useMemo(
    () => filterTree(treeNodes, searchQuery),
    [treeNodes, searchQuery],
  );

  const activeNode = useMemo(
    () => (activeNodeId ? nodeMap.get(activeNodeId) ?? null : null),
    [activeNodeId, nodeMap],
  );

  const activeValue = useMemo(() => {
    if (!activeNodeId) return undefined;
    const node = nodeMap.get(activeNodeId);
    if (!node) return undefined;
    if ("value" in node) {
      return node.value;
    }
    if (!parsedJson) return undefined;
    return getValueAtPath(parsedJson, node.path.replace(/^[^\.]+\.?/, ""));
  }, [activeNodeId, nodeMap, parsedJson]);

  const toggleNode = (nodeId: string) => {
    setExpandedNodes((previous) => {
      const next = new Set(previous);
      if (next.has(nodeId)) {
        next.delete(nodeId);
      } else {
        next.add(nodeId);
      }
      return next;
    });
  };

  const renderTree = (nodes: TreeNode[]) =>
    nodes.map((node) => {
      const hasChildren = Boolean(node.children?.length);
      const expanded = expandedNodes.has(node.id);
      const selected = activeNodeId === node.id;

      return (
        <div key={node.id} className="space-y-2">
          <button
            type="button"
            onClick={() => {
              setActiveNodeId(node.id);
              if (hasChildren) toggleNode(node.id);
            }}
            className={clsx(
              "flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left",
              selected ? "bg-indigo-50 text-indigo-700" : "text-slate-700",
            )}
          >
            {hasChildren ? (
              <span className="text-slate-500">
                {expanded ? (
                  <ChevronDownIcon className="size-4" />
                ) : (
                  <ChevronRightIcon className="size-4" />
                )}
              </span>
            ) : (
              <span className="size-4" />
            )}
            <div className="flex flex-col">
              <span className="text-sm font-medium">{node.label}</span>
              {!hasChildren && (
                <span className="text-xs text-slate-500">{node.path}</span>
              )}
            </div>
          </button>
          {hasChildren && expanded && (
            <div className="border-l border-slate-100 pl-4">
              {renderTree(node.children!)}
            </div>
          )}
        </div>
      );
    });

  const sendToCleansing = async () => {
    if (!context) return;
    setSending(true);
    setFeedback({ state: "loading" });

    try {
      let response: Response;
      const snapshotRawJson = snapshot?.rawJson ?? context.rawJson;
      const cleansedId = context.metadata.cleansedId;

      if (cleansedId) {
        response = await fetch(`/api/ingestion/resume/${encodeURIComponent(cleansedId)}`, {
          method: "POST",
        });
      } else if (context.mode === "s3" && context.sourceUri) {
        response = await fetch("/api/ingestion/s3", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ sourceUri: context.sourceUri }),
        });
      } else if (snapshotRawJson) {
        const parsed = safeJsonParse(snapshotRawJson);
        if (!parsed) {
          throw new Error("Original JSON is no longer available.");
        }
        response = await fetch("/api/ingestion/payload", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ payload: parsed }),
        });
      } else {
        throw new Error("No payload available to send to cleansing.");
      }

      const payload = await response.json();
      let storageResult: PersistenceResult | undefined;

      if (response.ok) {
        storageResult = saveCleansedContext(
          buildCleansedContextPayload(context.metadata, payload),
        );
        if (!storageResult.ok) {
          console.warn(
            "Unable to cache cleansed response locally; continuing without snapshot.",
            storageResult.reason,
          );
        }
      }

      setFeedback({
        state: response.ok ? "success" : "error",
        message: response.ok
          ? composeSuccessMessage(storageResult)
          : payload?.error ?? "Backend rejected the request.",
      });

      if (response.ok) {
        router.push("/cleansing");
      }
    } catch (error) {
      setFeedback({
        state: "error",
        message:
          error instanceof Error ? error.message : "Failed to send to cleansing.",
      });
    } finally {
      setSending(false);
    }
  };

  if (!hydrated) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50">
        <div className="rounded-3xl border border-slate-200 bg-white p-10 text-center shadow-sm">
          <p className="text-xs uppercase tracking-wide text-slate-400">Extraction</p>
          <h1 className="mt-3 text-lg font-semibold text-slate-900">Preparing workspace…</h1>
          <p className="mt-2 text-sm text-slate-500">Loading your latest extraction context.</p>
        </div>
      </div>
    );
  }

  if (!context) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50">
        <div className="rounded-3xl border border-slate-200 bg-white p-10 shadow-sm">
          <p className="text-lg font-semibold text-slate-900">
            Extraction context not found.
          </p>
          <p className="mt-2 text-sm text-slate-500">
            Start from the ingestion page to select a file or payload.
          </p>
          <button
            type="button"
            onClick={() => router.push("/ingestion")}
            className="mt-6 rounded-full bg-slate-900 px-6 py-2 text-sm font-semibold text-white"
          >
            Back to Ingestion
          </button>
        </div>
      </div>
    );
  }

  const sourceLabel = describeSourceLabel(
    context.metadata.sourceType ?? context.metadata.source,
    context.metadata.source,
  );
  const sourceIdentifier =
    context.metadata.sourceIdentifier ?? context.metadata.source ?? "—";

  return (
    <PipelineShell currentStep="extraction">
      <StageHero
        title="Extraction"
        description="Data extracted and converted to JSON format (Postgres/Neon)."
        actionsSlot={<FeedbackPill feedback={feedback} />}
      />

      <main className="mx-auto flex max-w-6xl flex-col gap-6 px-6 py-10">
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
          <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-xs uppercase tracking-wide text-slate-400">
                  File structure
                </p>
                <h2 className="text-lg font-semibold text-slate-900">
                  {context.metadata.name}
                </h2>
              </div>
              <button
                type="button"
                onClick={sendToCleansing}
                disabled={sending}
                className={clsx(
                  "inline-flex items-center gap-2 rounded-full bg-slate-900 px-4 py-2 text-xs font-semibold text-white",
                  sending && "cursor-not-allowed opacity-60",
                )}
              >
                {sending ? (
                  <>
                    <ArrowPathIcon className="size-4 animate-spin" /> Sending…
                  </>
                ) : (
                  "Send to Cleansing"
                )}
              </button>
            </div>
            <div className="mt-4 flex items-center gap-2 rounded-full border border-slate-200 bg-slate-50 px-3 py-1.5 text-xs text-slate-600">
              <InboxStackIcon className="size-4 text-slate-500" />
              <span className="font-semibold text-slate-700">{sourceLabel}</span>
            </div>
            <div className="mt-4">
              <div className="relative">
                <MagnifyingGlassIcon className="pointer-events-none absolute left-3 top-2.5 size-4 text-slate-400" />
                <input
                  type="search"
                  placeholder="Search fields..."
                  value={searchQuery}
                  onChange={(event) => setSearchQuery(event.target.value)}
                  className="w-full rounded-2xl border border-slate-200 bg-slate-50 py-2 pl-9 pr-3 text-sm text-slate-900 focus:border-indigo-500 focus:bg-white focus:outline-none"
                />
              </div>
              <div className="mt-4 max-h-[420px] space-y-3 overflow-y-auto pr-2">
                {snapshotLoading && context?.snapshotId && (
                  <div className="rounded-2xl border border-slate-200 bg-white py-6 text-center text-sm text-slate-600">
                    Loading extracted data snapshot…
                  </div>
                )}
                {!snapshotLoading && snapshotError && context?.snapshotId && (
                  <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
                    <p className="font-semibold">Unable to load the cached structure.</p>
                    <p className="mt-1">{snapshotError}</p>
                    <button
                      type="button"
                      onClick={retrySnapshotFetch}
                      className="mt-3 rounded-full bg-amber-600 px-3 py-1 text-xs font-semibold text-white"
                    >
                      Retry download
                    </button>
                  </div>
                )}
                {filteredTree.length === 0 ? (
                  <div className="rounded-2xl border border-dashed border-slate-200 py-10 text-center text-sm text-slate-500">
                    Structure preview isn’t available yet. Re-run ingestion if this persists.
                  </div>
                ) : (
                  <div className="space-y-3">{renderTree(filteredTree)}</div>
                )}
              </div>
            </div>
          </section>

          <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
            <p className="text-xs uppercase tracking-wide text-slate-400">
              Data overview
            </p>
            <h2 className="text-lg font-semibold text-slate-900">
              Field details
            </h2>
            <div className="mt-4 space-y-3 rounded-2xl bg-slate-50 p-4">
              <div>
                <p className="text-xs uppercase tracking-wide text-slate-400">
                  Field
                </p>
                <p className="text-sm font-semibold text-slate-900">
                  {activeNode?.label ?? "Select a node"}
                </p>
                {activeNode && (
                  <p className="text-xs text-slate-500">{activeNode.path}</p>
                )}
              </div>
              <div>
                <p className="text-xs uppercase tracking-wide text-slate-400">
                  Original value
                </p>
                <pre className="max-h-48 overflow-y-auto rounded-xl bg-white p-3 text-sm text-slate-800">
                  {activeValue === undefined
                    ? "—"
                    : typeof activeValue === "object"
                      ? JSON.stringify(activeValue, null, 2)
                      : String(activeValue)}
                </pre>
              </div>
            </div>
          </section>
        </div>

        <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-semibold text-slate-900">File metadata</h3>
            <button
              type="button"
              onClick={() => {
                clearExtractionContext();
                router.push("/ingestion");
              }}
              className="text-xs font-semibold text-indigo-600"
            >
              Start over
            </button>
          </div>
          <dl className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <dt className="text-xs uppercase tracking-wide text-slate-400">Name</dt>
              <dd className="text-sm font-semibold text-slate-900">{context.metadata.name}</dd>
            </div>
            <div>
              <dt className="text-xs uppercase tracking-wide text-slate-400">Size</dt>
              <dd className="text-sm font-semibold text-slate-900">
                {formatBytes(context.metadata.size)}
              </dd>
            </div>
            <div>
              <dt className="text-xs uppercase tracking-wide text-slate-400">Source type</dt>
              <dd className="text-sm font-semibold text-slate-900">{sourceLabel}</dd>
            </div>
            <div>
              <dt className="text-xs uppercase tracking-wide text-slate-400">Source identifier</dt>
              <dd className="text-sm font-semibold text-slate-900 break-all">{sourceIdentifier}</dd>
            </div>
            <div>
              <dt className="text-xs uppercase tracking-wide text-slate-400">Cleansed ID</dt>
              <dd className="text-sm font-semibold text-slate-900">
                {context.metadata.cleansedId ?? "—"}
              </dd>
            </div>
            <div>
              <dt className="text-xs uppercase tracking-wide text-slate-400">Uploaded</dt>
              <dd className="text-sm font-semibold text-slate-900">
                {new Date(context.metadata.uploadedAt).toLocaleString()}
              </dd>
            </div>
          </dl>
        </section>
      </main>
    </PipelineShell>
  );
}