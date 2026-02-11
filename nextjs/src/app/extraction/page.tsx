"use client";

import {
  ArrowPathIcon,
  CheckCircleIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  ExclamationCircleIcon,
  InboxStackIcon,
  MagnifyingGlassIcon,
  DocumentIcon,
  DocumentTextIcon,
  Square2StackIcon,
  TableCellsIcon,
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
      ? "bg-slate-100 text-slate-900 border border-slate-200"
      : feedback.state === "error"
        ? "bg-slate-50 text-slate-500 border border-slate-200"
        : "bg-slate-50 text-slate-600",
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
  const [previewMode, setPreviewMode] = useState<"structured" | "raw">("structured");

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

  const renderTree = (nodes: TreeNode[], depth = 0) =>
    nodes.map((node) => {
      const hasChildren = Boolean(node.children?.length);
      const expanded = expandedNodes.has(node.id);
      const selected = activeNodeId === node.id;

      // Hierarchical Icons
      const Icon = hasChildren
        ? depth === 0
          ? Square2StackIcon
          : TableCellsIcon
        : DocumentIcon;

      return (
        <div key={node.id} className="space-y-1">
          <button
            type="button"
            onClick={() => {
              setActiveNodeId(node.id);
              if (hasChildren) toggleNode(node.id);
            }}
            className={clsx(
              "group flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left transition-all",
              selected ? "bg-black text-white shadow-md" : "text-slate-600 hover:bg-slate-50",
            )}
          >
            {hasChildren ? (
              <span className={clsx(selected ? "text-white" : "text-slate-400")}>
                {expanded ? (
                  <ChevronDownIcon className="size-3.5" />
                ) : (
                  <ChevronRightIcon className="size-3.5" />
                )}
              </span>
            ) : (
              <span className="size-3.5" />
            )}
            <Icon
              className={clsx(
                "size-4 shrink-0",
                selected ? "text-white" : "text-slate-400 group-hover:text-black",
              )}
            />
            <span className="truncate text-sm font-semibold">{node.label}</span>
          </button>
          {hasChildren && expanded && (
            <div className="ml-4 border-l border-slate-100 pl-2">
              {renderTree(node.children!, depth + 1)}
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
    <PipelineShell currentStep="extraction" breadcrumbExtra={context.metadata.name}>
      <StageHero
        title="Extraction"
        description="Data extracted and converted to JSON format (Postgres/Neon)."
        actionsSlot={<FeedbackPill feedback={feedback} />}
      />

      <main className="mx-auto flex max-w-[1600px] flex-col gap-6 px-4 py-6 sm:px-6 sm:py-8 lg:px-8">
        <div className="grid h-auto lg:h-[calc(100vh-20rem)] lg:min-h-[600px] gap-6 lg:grid-cols-[320px_1fr_320px]">
          {/* Left Pane: File Structure */}
          <section className="flex flex-col rounded-3xl border border-slate-200 bg-white p-4 sm:p-6 shadow-sm overflow-hidden max-h-[400px] lg:max-h-none">
            <div className="flex items-center justify-between mb-6">
              <h3 className="text-sm font-bold uppercase tracking-widest text-slate-400">
                File Structure
              </h3>
            </div>

            <div className="relative mb-6">
              <MagnifyingGlassIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-slate-400" />
              <input
                type="search"
                placeholder="Search..."
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                className="w-full rounded-xl border border-slate-100 bg-slate-50 py-2.5 pl-10 pr-4 text-sm text-black focus:border-black focus:bg-white focus:outline-none transition-all"
              />
            </div>

            <div className="flex-1 overflow-y-auto pr-2 scrollbar-thin scrollbar-thumb-slate-200 scrollbar-track-transparent">
              {snapshotLoading && context?.snapshotId && (
                <div className="rounded-2xl border border-slate-100 bg-slate-50 py-12 text-center text-sm text-slate-400 animate-pulse">
                  Loading structure...
                </div>
              )}
              {!snapshotLoading && filteredTree.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-slate-200 py-12 text-center text-sm text-slate-400">
                  No matches found.
                </div>
              ) : (
                <div className="space-y-1">{renderTree(filteredTree)}</div>
              )}
            </div>
          </section>

          {/* Center Pane: Data Preview */}
          <section className="flex flex-col rounded-3xl border border-slate-200 bg-white shadow-sm overflow-hidden min-h-[400px]">
            <div className="flex items-center justify-between border-b border-slate-100 p-4 sm:p-6">
              <div className="flex items-center gap-4">
                <div className="flex size-10 items-center justify-center rounded-xl bg-slate-50 text-black">
                  <Square2StackIcon className="size-5" />
                </div>
                <div>
                  <h2 className="text-lg font-bold text-black leading-none">
                    {activeNode?.label ?? "Select a node"}
                  </h2>
                  <p className="mt-1.5 text-xs font-medium text-slate-400">
                    {activeNode?.path ?? "Choose an item from the structure"}
                  </p>
                </div>
              </div>

              <div className="flex items-center rounded-xl bg-slate-100 p-1">
                <button
                  onClick={() => setPreviewMode("raw")}
                  className={clsx(
                    "rounded-lg px-4 py-1.5 text-xs font-bold transition-all",
                    previewMode === "raw" ? "bg-white text-black shadow-sm" : "text-slate-500",
                  )}
                >
                  Raw
                </button>
                <button
                  onClick={() => setPreviewMode("structured")}
                  className={clsx(
                    "rounded-lg px-4 py-1.5 text-xs font-bold transition-all",
                    previewMode === "structured" ? "bg-white text-black shadow-sm" : "text-slate-500",
                  )}
                >
                  Structured
                </button>
              </div>
            </div>

            <div className="flex-1 overflow-y-auto p-6 scrollbar-thin scrollbar-thumb-slate-200 scrollbar-track-transparent">
              {previewMode === "structured" ? (
                <div className="rounded-2xl border border-slate-100 overflow-hidden">
                  <table className="w-full text-left text-sm">
                    <thead className="bg-slate-50 text-[10px] font-bold uppercase tracking-widest text-slate-400">
                      <tr>
                        <th className="px-6 py-4">Field Name</th>
                        <th className="px-6 py-4">Field Value</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 bg-white">
                      {activeNode && (
                        <tr className="bg-slate-50/30">
                          <td className="px-6 py-5 font-bold text-black">{activeNode.label}</td>
                          <td className="px-6 py-5 text-slate-600 italic">
                            {activeValue === undefined
                              ? "—"
                              : typeof activeValue === "object"
                                ? Array.isArray(activeValue)
                                  ? `Array (${activeValue.length} items)`
                                  : "Object"
                                : String(activeValue)}
                          </td>
                        </tr>
                      )}
                      {activeNode?.children?.map((child) => {
                        const rawChildValue = parsedJson ? getValueAtPath(parsedJson, child.path.replace(/^[^\.]+\.?/, "")) : undefined;
                        const childValue = child.value !== undefined ? child.value : rawChildValue;

                        return (
                          <tr key={child.id}>
                            <td className="px-6 py-5 font-bold text-black">{child.label}</td>
                            <td className="px-6 py-5 text-slate-600">
                              {childValue === undefined
                                ? "—"
                                : typeof childValue === "object"
                                  ? Array.isArray(childValue)
                                    ? `Array (${childValue.length} items)`
                                    : "Object"
                                  : String(childValue)}
                            </td>
                          </tr>
                        );
                      })}
                      {!activeNode && (
                        <tr>
                          <td colSpan={2} className="px-6 py-10 text-center text-slate-400 italic">
                            Select an item from the file structure to view details
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="h-full rounded-2xl bg-slate-900 p-6 shadow-inner">
                  <pre className="h-full overflow-auto text-sm font-mono text-slate-300 leading-relaxed scrollbar-thin scrollbar-thumb-slate-700 scrollbar-track-transparent">
                    {activeValue === undefined
                      ? "// Select a node to view raw content"
                      : JSON.stringify(activeValue, null, 2)}
                  </pre>
                </div>
              )}
            </div>

            <div className="p-6 bg-white border-t border-slate-100 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="text-[10px] font-bold uppercase tracking-widest text-slate-400">
                  Status
                </span>
                <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-100 px-2.5 py-1 text-[10px] font-bold text-slate-900 border border-slate-200">
                  <span className="size-1 rounded-full bg-black" />
                  Extracted
                </span>
              </div>
              <button
                type="button"
                onClick={sendToCleansing}
                disabled={sending}
                className={clsx(
                  "rounded-xl bg-black px-8 py-3 text-xs font-bold uppercase tracking-widest text-white shadow-lg shadow-black/20 transition-all hover:bg-slate-800 active:scale-95",
                  sending && "cursor-not-allowed opacity-60",
                )}
              >
                {sending ? "Processing..." : "Send to Cleansing"}
              </button>
            </div>
          </section>

          {/* Right Pane: File Metadata */}
          <section className="flex flex-col rounded-3xl border border-slate-200 bg-white p-4 sm:p-6 shadow-sm">
            <h3 className="text-sm font-bold uppercase tracking-widest text-slate-400 mb-6">
              File Metadata
            </h3>

            <div className="space-y-6 flex-1">
              {[
                { label: "Locale", value: context.metadata.locale ?? "—" },
                { label: "Page ID", value: context.metadata.pageId ?? "—" },
                { label: "Name", value: context.metadata.name },
                { label: "Size", value: formatBytes(context.metadata.size) },
                { label: "Source Type", value: sourceLabel },
                { label: "Uploaded", value: new Date(context.metadata.uploadedAt).toLocaleString() },
              ].map((item) => (
                <div key={item.label} className="group">
                  <dt className="text-[10px] font-bold uppercase tracking-widest text-slate-400 transition-colors group-hover:text-black">
                    {item.label}
                  </dt>
                  <dd className="mt-1.5 text-sm font-bold text-black break-all">
                    {item.value}
                  </dd>
                </div>
              ))}
            </div>

            <div className="mt-8 pt-6 border-t border-slate-100">
              <button
                type="button"
                onClick={() => {
                  clearExtractionContext();
                  router.push("/ingestion");
                }}
                className="w-full rounded-xl border border-slate-200 py-3 text-xs font-bold uppercase tracking-widest text-slate-400 hover:border-black hover:text-black transition-all"
              >
                Start Over
              </button>
            </div>
          </section>
        </div>
      </main>
    </PipelineShell>
  );
}
