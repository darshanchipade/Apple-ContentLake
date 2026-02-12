"use client";

import {
  ArrowPathIcon,
  CheckCircleIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  CircleStackIcon,
  CodeBracketIcon,
  CubeIcon,
  DocumentTextIcon,
  ExclamationCircleIcon,
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
import { describeSourceLabel, inferSourceType, pickString } from "@/lib/source";
import { formatBytes } from "../../lib/format";

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
      ? "bg-primary-soft text-primary"
      : feedback.state === "error"
        ? "bg-rose-50 text-rose-700"
        : "bg-primary-soft text-primary",
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
  const [previewMode, setPreviewMode] = useState<"structured" | "raw">("structured");
  const [feedback, setFeedback] = useState<Feedback>({ state: "idle" });
  const [sending, setSending] = useState(false);
  const [nodeMap, setNodeMap] = useState<Map<string, TreeNode>>(new Map());
  const [snapshot, setSnapshot] = useState<ExtractionSnapshot | null>(null);
  const [snapshotLoading, setSnapshotLoading] = useState(false);
  const [snapshotError, setSnapshotError] = useState<string | null>(null);
  const [snapshotVersion, setSnapshotVersion] = useState(0);

  const [openSections, setOpenSections] = useState<Set<string>>(new Set(["structure", "preview"]));

  const toggleSection = (section: string) => {
    setOpenSections((prev) => {
      const next = new Set(prev);
      if (next.has(section)) next.delete(section);
      else next.add(section);
      return next;
    });
  };

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
      const getRootName = () => {
        const payload = loadExtractionContext();
        return payload?.metadata?.name || "Content.JSON";
      };

      if (tree && tree.length) {
        // If it's already wrapped, don't wrap again
        if (tree.length === 1 && tree[0].id === "root") {
          applyTreeFromNodes(tree);
        } else {
          const rootNode: TreeNode = {
            id: "root",
            label: getRootName(),
            path: "root",
            type: "object",
            children: tree,
          };
          applyTreeFromNodes([rootNode]);
        }
        setParsedJson(rawJson ? safeJsonParse(rawJson) : null);
        return;
      }

      if (rawJson) {
        const parsed = safeJsonParse(rawJson);
        setParsedJson(parsed);
        if (parsed) {
          const nodes = buildTreeFromJson(parsed, ["root"], { value: 0 });
          if (nodes.length) {
            const rootNode: TreeNode = {
              id: "root",
              label: getRootName(),
              path: "root",
              type: "object",
              children: nodes,
            };
            applyTreeFromNodes([rootNode]);
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

  const renderTree = (nodes: TreeNode[], level = 0) =>
    nodes.map((node) => {
      const hasChildren = Boolean(node.children?.length);
      const expanded = expandedNodes.has(node.id);
      const selected = activeNodeId === node.id;
      const isRoot = node.id === "root";

      return (
        <div key={node.id} className="select-none">
          <div
            className={clsx(
              "group flex items-center gap-2 py-1.5 px-2 rounded-md hover:bg-primary-soft/30 cursor-pointer transition-colors",
              selected && "bg-primary-soft/70",
              isRoot && "bg-primary-soft/20 mb-2",
              level > 0 && !isRoot && "ml-2 sm:ml-4",
            )}
            onClick={() => {
              setActiveNodeId(node.id);
              if (hasChildren) toggleNode(node.id);
            }}
          >
            {hasChildren ? (
              <ChevronDownIcon
                className={clsx(
                  "size-3 text-gray-400 transition-transform",
                  !expanded && "-rotate-90",
                )}
              />
            ) : (
              <div className="size-3" />
            )}

            {isRoot ? (
              <div className="flex items-center gap-2 flex-1">
                <div className="flex items-center gap-1.5">
                  <div className="size-4 rounded bg-primary flex items-center justify-center">
                    <CheckCircleIcon className="size-3 text-white" />
                  </div>
                  <div className="p-1 rounded bg-purple-100 text-purple-600">
                    <CodeBracketIcon className="size-3.5" />
                  </div>
                </div>
                <span className="text-sm font-semibold text-gray-900">{node.label}</span>
              </div>
            ) : (
              <>
                <input
                  type="checkbox"
                  className="size-3.5 rounded border-gray-300 text-primary focus:ring-primary"
                  checked={true}
                  readOnly
                  onClick={(e) => e.stopPropagation()}
                />

                {hasChildren ? (
                  <CircleStackIcon className="size-4 text-primary" />
                ) : (
                  <CubeIcon className="size-4 text-gray-400" />
                )}

                <span
                  className={clsx(
                    "text-sm flex-1",
                    selected ? "text-primary font-bold" : "text-gray-700",
                  )}
                >
                  {node.label}
                </span>
                {!hasChildren && (
                  <span className="text-[10px] text-gray-400 font-mono uppercase px-1.5 py-0.5 bg-gray-100 rounded">
                    string
                  </span>
                )}
              </>
            )}
          </div>
          {hasChildren && expanded && (
            <div className={clsx("mt-0.5 border-l border-gray-100", isRoot ? "ml-2 sm:ml-4" : "ml-1 sm:ml-1.5")}>
              {renderTree(node.children!, level + 1)}
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
        const idToPass = context.metadata.cleansedId ?? payload.body?.cleansedId;
        if (idToPass) {
          router.push(`/cleansing?id=${encodeURIComponent(idToPass)}`);
        } else {
          router.push("/cleansing");
        }
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
      <PipelineShell currentStep="extraction" showTracker={false}>
        <div className="flex h-[calc(100vh-64px)] items-center justify-center bg-gray-50/50 p-8">
          <div className="max-w-md w-full rounded-2xl border border-gray-200 bg-white p-10 text-center shadow-sm">
            <h2 className="text-xl font-bold text-gray-900">Extraction context not found</h2>
            <p className="mt-4 text-sm text-gray-500">
              Start from the ingestion page to select a file or payload.
            </p>
            <button
              type="button"
              onClick={() => router.push("/ingestion")}
              className="mt-8 btn-primary w-full"
            >
              Back to Ingestion
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
  const sourceIdentifier =
    context.metadata.sourceIdentifier ?? context.metadata.source ?? "—";

  return (
    <PipelineShell currentStep="extraction">
      <div className="min-h-[calc(100vh-4rem)] bg-background">
        <section className="border-b border-slate-200 bg-white">
          <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-3 px-4 py-4 sm:px-6 sm:py-6 lg:px-8">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between text-left">
              <div className="space-y-1 sm:space-y-2">
                <h1 className="text-2xl sm:text-3xl font-bold text-black">Extraction</h1>
                <p className="text-xs sm:text-sm font-medium text-slate-500 lg:max-w-2xl">
                  Inspect the source JSON structure and preview individual field values before proceeding.
                </p>
              </div>
              <div className="flex flex-col sm:flex-row items-center justify-center lg:justify-end gap-4">
                <FeedbackPill feedback={feedback} />
                <button
                  onClick={sendToCleansing}
                  disabled={sending}
                  className="w-full sm:w-auto rounded-full bg-primary px-8 py-3 text-sm font-semibold text-white transition hover:bg-accent flex items-center justify-center gap-2 shadow-lg"
                >
                  <span className="whitespace-nowrap">{sending ? "Processing..." : "Continue"}</span>
                  <ChevronRightIcon className="size-4 shrink-0" />
                </button>
              </div>
            </div>
          </div>
        </section>

        <main className="mx-auto grid max-w-[1600px] gap-6 px-4 py-6 sm:px-6 sm:py-8 lg:grid-cols-12 items-stretch">
          {/* File Structure */}
          <div className="lg:col-span-3 bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden flex flex-col lg:h-[calc(100vh-22rem)] lg:min-h-[600px]">
            <button
              type="button"
              onClick={() => toggleSection("structure")}
              className="w-full flex items-center justify-between p-6 text-left lg:pointer-events-none shrink-0"
            >
              <h2 className="text-lg font-bold">File Structure</h2>
              <div className="lg:hidden">
                {openSections.has("structure") ? (
                  <ChevronDownIcon className="size-5 text-slate-400" />
                ) : (
                  <ChevronRightIcon className="size-5 text-slate-400" />
                )}
              </div>
            </button>

            <div className={clsx(
              "flex-col flex-1 min-h-0",
              openSections.has("structure") ? "flex" : "hidden lg:flex"
            )}>
              <div className="px-6 pb-6 border-b border-slate-100 shrink-0">
                <div className="flex items-center justify-between p-3 bg-slate-50 rounded-xl border border-slate-100">
                  <div className="flex items-center gap-3">
                    <div className="p-2 bg-primary/10 rounded-lg">
                      <CheckCircleIcon className="size-6 text-primary" />
                    </div>
                    <div>
                      <div className="flex items-center gap-1.5">
                        <span className="font-semibold text-sm truncate max-w-[100px]">{context.metadata.name}</span>
                        <CheckCircleIcon className="size-4 text-primary" />
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <div className="p-6 flex-1 flex flex-col min-h-0">
                <div className="relative mb-6 flex-shrink-0">
                  <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-gray-400" />
                  <input
                    type="text"
                    placeholder="Search fields..."
                    className="w-full pl-10 pr-4 py-2 bg-gray-50 border border-gray-200 rounded-xl text-base lg:text-sm focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                  />
                </div>

                <div className="flex-1 overflow-auto pr-2 custom-scrollbar min-h-0">
                  <div className="min-w-max pb-4">
                    {snapshotLoading && context?.snapshotId && (
                      <div className="py-10 text-center text-sm text-gray-400">Loading structure...</div>
                    )}
                    {filteredTree.length === 0 ? (
                      <div className="py-10 text-center text-sm text-gray-400">No structure available</div>
                    ) : (
                      renderTree(filteredTree)
                    )}
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div className="lg:col-span-6 flex flex-col">
            {/* Data Preview */}
            <div className="bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden flex flex-col lg:h-[calc(100vh-22rem)] lg:min-h-[600px]">
              <button
                type="button"
                onClick={() => toggleSection("preview")}
                className="w-full flex items-center justify-between p-6 text-left lg:pointer-events-none"
              >
                <h2 className="text-lg font-bold">Data Preview</h2>
                <div className="lg:hidden">
                  {openSections.has("preview") ? (
                    <ChevronDownIcon className="size-5 text-slate-400" />
                  ) : (
                    <ChevronRightIcon className="size-5 text-slate-400" />
                  )}
                </div>
              </button>

              <div className={clsx(
                "flex-col flex-1 min-h-0",
                openSections.has("preview") ? "flex" : "hidden lg:flex"
              )}>
                <div className="px-6 pb-6 border-b border-slate-100 flex items-center justify-between bg-white">
                  <div className="flex p-1 bg-slate-100 rounded-lg">
                    <button
                      onClick={() => setPreviewMode("structured")}
                      className={clsx(
                        "flex items-center gap-2 px-4 py-1.5 text-xs font-semibold rounded-md transition-all",
                        previewMode === "structured" ? "bg-white shadow-sm text-gray-900" : "text-gray-500 hover:text-gray-700"
                      )}
                    >
                      Structured
                    </button>
                    <button
                      onClick={() => setPreviewMode("raw")}
                      className={clsx(
                        "flex items-center gap-2 px-4 py-1.5 text-xs font-semibold rounded-md transition-all",
                        previewMode === "raw" ? "bg-white shadow-sm text-gray-900" : "text-gray-500 hover:text-gray-700"
                      )}
                    >
                      Raw
                    </button>
                  </div>
                </div>

                <div className="p-4 lg:p-6 bg-slate-50/50 flex-1 flex flex-col overflow-hidden min-h-0">
                  <div className="flex items-center gap-2 text-xs font-medium text-gray-500 mb-4">
                    <span className="truncate max-w-[150px]">{context.metadata.name}</span>
                    {activeNode && (
                      <>
                        <ChevronRightIcon className="size-3" />
                        <span className="truncate max-w-[150px]">{activeNode.label}</span>
                      </>
                    )}
                  </div>

                  <div className="flex-1 bg-white rounded-xl border border-gray-200 overflow-hidden text-sm min-h-0">
                    <div className="h-full overflow-y-auto custom-scrollbar">
                      {previewMode === "raw" ? (
                        <div className="p-6 font-mono">
                          <pre className="text-gray-800">
                            {activeValue === undefined
                              ? "/* Select a field to see its raw value */"
                              : typeof activeValue === "object"
                                ? JSON.stringify(activeValue, null, 2)
                                : String(activeValue)}
                          </pre>
                        </div>
                      ) : (
                        <div className="h-full flex flex-col">
                          {activeNode ? (
                            <table className="w-full text-left border-collapse">
                              <thead className="sticky top-0 bg-gray-50 shadow-sm z-10">
                                <tr>
                                  <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider border-b border-gray-100">
                                    Field Name
                                  </th>
                                  <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider border-b border-gray-100">
                                    Value
                                  </th>
                                </tr>
                              </thead>
                              <tbody className="divide-y divide-gray-50">
                                {(() => {
                                  const getNodesToDisplay = () => {
                                    if (!activeNode) return [];
                                    if (activeNode.children && activeNode.children.length > 0) {
                                      return activeNode.children;
                                    }
                                    return [activeNode];
                                  };

                                  const nodesToDisplay = getNodesToDisplay();

                                  return nodesToDisplay.map((node) => {
                                    const val =
                                      "value" in node
                                        ? node.value
                                        : getValueAtPath(
                                            parsedJson,
                                            node.path.replace(/^[^\.]+\.?/, ""),
                                          );

                                    return (
                                      <tr key={node.id} className="hover:bg-gray-50/50">
                                        <td className="px-6 py-4 font-medium text-gray-700">
                                          {node.label}
                                        </td>
                                        <td className="px-6 py-4 text-gray-600 break-all max-w-xs">
                                          {val === undefined
                                            ? "—"
                                            : typeof val === "object"
                                              ? JSON.stringify(val)
                                              : String(val)}
                                        </td>
                                      </tr>
                                    );
                                  });
                                })()}
                              </tbody>
                            </table>
                          ) : (
                            <div className="flex-1 flex items-center justify-center text-gray-400 italic">
                              Select a field to preview data
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* File Metadata Sidebar */}
          <div className="lg:col-span-3 bg-white rounded-3xl border border-slate-200 p-6 shadow-sm lg:h-[calc(100vh-22rem)] lg:min-h-[600px] overflow-y-auto custom-scrollbar">
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-lg font-bold">Metadata</h2>
              <button
                type="button"
                onClick={() => {
                  clearExtractionContext();
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
                <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Identifier</p>
                <p className="text-sm font-bold text-gray-900 break-all">{sourceIdentifier}</p>
              </div>
              <div className="grid grid-cols-2 gap-4">
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
                <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Page ID</p>
                <p className="text-sm font-bold text-gray-900 break-all">{context.metadata.pageId ?? "—"}</p>
              </div>
              <div>
                <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1">Uploaded</p>
                <p className="text-[11px] font-bold text-gray-900">
                  {new Date(context.metadata.uploadedAt).toLocaleString()}
                </p>
              </div>
            </div>
          </div>
        </main>
      </div>
    </PipelineShell>
  );
}
