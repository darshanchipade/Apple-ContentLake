"use client";

import {
  ArrowDownTrayIcon,
  ArrowPathIcon,
  ArrowUpTrayIcon,
  CheckCircleIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  CloudArrowUpIcon,
  DocumentTextIcon,
  ExclamationCircleIcon,
  InboxStackIcon,
  MagnifyingGlassIcon,
  ServerStackIcon,
  TrashIcon,
} from "@heroicons/react/24/outline";
import clsx from "clsx";
import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { PipelineShell } from "@/components/PipelineShell";
import { StageHero } from "@/components/StageHero";
import {
  TreeNode,
  buildTreeFromJson,
  filterTree,
  gatherLeafNodes,
} from "@/lib/tree";
import {
  saveExtractionContext,
  type ExtractionContext,
  type PersistenceResult,
} from "@/lib/extraction-context";
import { storeClientSnapshot } from "@/lib/client/snapshot-store";
import type { ExtractionSnapshot } from "@/lib/extraction-snapshot";
import { extractLocaleAndPageId, pickLocale, pickPageId } from "@/lib/metadata";
import { describeSourceLabel, inferSourceType, pickString } from "@/lib/source";
import {
  readUploadHistory,
  writeUploadHistory,
  type UploadHistoryItem,
  type UploadStatus,
} from "@/lib/upload-history";

type UploadTab = "local" | "api" | "s3";

type ApiFeedback = {
  state: "idle" | "loading" | "success" | "error";
  message?: string;
};

const uploadTabs = [
  {
    id: "s3" as const,
    title: "Amazon S3 / Cloud",
    description: "Ingest directly from s3:// or classpath URIs.",
    icon: CloudArrowUpIcon,
    disabled: false,
  },
  {
    id: "local" as const,
    title: "Local Upload",
    description: "Upload files directly from your device.",
    icon: ArrowUpTrayIcon,
    disabled: false,
  },
  {
    id: "api" as const,
    title: "API Endpoint",
    description: "Send JSON payloads programmatically.",
    icon: ServerStackIcon,
    disabled: false,
  },
];

const statusStyles: Record<
  UploadStatus,
  { label: string; className: string; dot: string }
> = {
  uploading: {
    label: "Uploading",
    className: "bg-slate-50 text-slate-700",
    dot: "bg-slate-400",
  },
  success: {
    label: "Accepted",
    className: "bg-slate-100 text-slate-900",
    dot: "bg-slate-900",
  },
  error: {
    label: "Error",
    className: "bg-slate-50 text-slate-500",
    dot: "bg-slate-300",
  },
};

const formatBytes = (bytes: number) => {
  if (!Number.isFinite(bytes)) return "—";
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.floor(Math.log(bytes) / Math.log(1024));
  const value = bytes / Math.pow(1024, index);
  return `${value.toFixed(value > 9 || index === 0 ? 0 : 1)} ${units[index]}`;
};

const safeJsonParse = (value: string) => {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
};

const getFileLabel = (fileName: string) => {
  const extension = fileName.split(".").pop()?.toLowerCase();
  switch (extension) {
    case "json":
      return { label: "JSON", style: "bg-slate-100 text-slate-900" };
    case "pdf":
      return { label: "PDF", style: "bg-slate-100 text-slate-900" };
    case "xls":
    case "xlsx":
      return { label: "XLS", style: "bg-slate-100 text-slate-900" };
    case "doc":
    case "docx":
      return { label: "DOC", style: "bg-slate-100 text-slate-900" };
    default:
      return { label: "FILE", style: "bg-slate-100 text-slate-600" };
  }
};

const describeExtractionPersistenceError = (result?: PersistenceResult) => {
  if (!result) {
    return "Extraction context could not be cached in this browser.";
  }
  switch (result.reason) {
    case "quota":
      return "Browser storage is full. Clear other extraction tabs or reduce the payload size and try again.";
    case "ssr":
      return "Extraction context can only be saved in a browser tab.";
    default:
      return "Extraction context could not be cached locally. Check the console for details.";
  }
};

const FeedbackPill = ({ feedback }: { feedback: ApiFeedback }) => {
  if (feedback.state === "idle") {
    return null;
  }

  const className = clsx(
    "inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold",
    feedback.state === "success"
      ? "bg-slate-100 text-slate-900"
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

export default function IngestionPage() {
  const router = useRouter();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const pendingLocalUploadIdRef = useRef<string | null>(null);
  const pendingApiUploadIdRef = useRef<string | null>(null);
  const [activeTab, setActiveTab] = useState<UploadTab>("local");
  const [localFile, setLocalFile] = useState<File | null>(null);
  const [localFileText, setLocalFileText] = useState<string | null>(null);
  const [apiPayload, setApiPayload] = useState("");
  const [s3Uri, setS3Uri] = useState("");
  const [treeNodes, setTreeNodes] = useState<TreeNode[]>([]);
  const [previewLabel, setPreviewLabel] = useState("Awaiting content");
  const [expandedNodes, setExpandedNodes] = useState<Set<string>>(new Set());
  const [searchQuery, setSearchQuery] = useState("");
  const [uploads, setUploads] = useState<UploadHistoryItem[]>([]);
  const [downloadInFlight, setDownloadInFlight] = useState<string | null>(null);
  const [historyHydrated, setHistoryHydrated] = useState(false);
  const [extractFeedback, setExtractFeedback] = useState<ApiFeedback>({
    state: "idle",
  });
  const [apiFeedback, setApiFeedback] = useState<ApiFeedback>({ state: "idle" });
  const [s3Feedback, setS3Feedback] = useState<ApiFeedback>({ state: "idle" });
  const [extracting, setExtracting] = useState(false);

  const filteredTree = useMemo(
    () => filterTree(treeNodes, searchQuery),
    [treeNodes, searchQuery],
  );

  const previewLeaves = useMemo(() => {
    if (!treeNodes.length) return [];
    return treeNodes.flatMap((node) => gatherLeafNodes(node));
  }, [treeNodes]);

  useEffect(() => {
    if (activeTab === "s3") {
      setTreeNodes([]);
      setPreviewLabel("Structure preview unavailable for S3/classpath sources.");
    } else if (activeTab === "local" && localFileText) {
      const parsed = safeJsonParse(localFileText);
      if (parsed) {
        seedPreviewTree(localFile?.name ?? "Local JSON", parsed);
      }
    } else if (activeTab === "api" && apiPayload.trim()) {
      const parsed = safeJsonParse(apiPayload);
      if (parsed) {
        seedPreviewTree("API payload", parsed);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab]);

  useEffect(() => {
    const history = readUploadHistory();
    if (history.length) {
      setUploads(history);
    }
    setHistoryHydrated(true);
  }, []);

  useEffect(() => {
    if (!historyHydrated) return;
    writeUploadHistory(uploads);
  }, [uploads, historyHydrated]);

  const seedPreviewTree = (label: string, payload: unknown): TreeNode[] => {
    const counter = { value: 0 };
    const children = buildTreeFromJson(payload, [], counter);
    if (!children.length) {
      setTreeNodes([]);
      setPreviewLabel("Unable to derive structure from payload.");
      setExpandedNodes(new Set());
      return [];
    }
    const rootNode: TreeNode = {
      id: label,
      label,
      path: label,
      type: "object",
      children,
    };
    const nodes = [rootNode];
    setTreeNodes(nodes);
    setPreviewLabel(label);
    setExpandedNodes(new Set([rootNode.id]));
    return nodes;
  };

  const handleLocalFileSelection = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const [file] = Array.from(files);
    setLocalFile(file);
    setExtractFeedback({ state: "idle" });

     const uploadId = crypto.randomUUID();
     pendingLocalUploadIdRef.current = uploadId;
     setUploads((previous) => [
       {
         id: uploadId,
         name: file.name,
         size: file.size,
         type: file.type || file.name.split(".").pop() || "file",
         source: "Local",
         status: "uploading",
         createdAt: Date.now(),
       },
       ...previous,
     ]);

    if (file.name.toLowerCase().endsWith(".json")) {
      const text = await file.text();
      setLocalFileText(text);
      const parsed = safeJsonParse(text);
      if (parsed) {
        seedPreviewTree(file.name, parsed);
        const { locale, pageId } = extractLocaleAndPageId(parsed);
        if (locale || pageId) {
          setUploads((previous) =>
            previous.map((upload) =>
              upload.id === uploadId
                ? {
                    ...upload,
                    locale: locale ?? upload.locale,
                    pageId: pageId ?? upload.pageId,
                  }
                : upload,
            ),
          );
        }
      } else {
        setTreeNodes([]);
        setPreviewLabel("Uploaded JSON could not be parsed.");
      }
    } else {
      setLocalFileText(null);
      setTreeNodes([]);
      setPreviewLabel("Select a JSON file to preview its structure.");
    }

    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  };

  const handleExtractData = async () => {
    setExtracting(true);
    setExtractFeedback({ state: "loading", message: "Dispatching to backend..." });

    try {
      if (activeTab === "local") {
        await processLocalExtraction();
      } else if (activeTab === "api") {
        await processApiExtraction();
      } else {
        await processS3Extraction();
      }
    } catch (error) {
      setExtractFeedback({
        state: "error",
        message:
          error instanceof Error
            ? error.message
            : "Extraction failed unexpectedly.",
      });
      setExtracting(false);
    }
  };

  const ensurePendingApiUpload = (size: number) => {
    if (pendingApiUploadIdRef.current) {
      setUploads((previous) =>
        previous.map((upload) =>
          upload.id === pendingApiUploadIdRef.current ? { ...upload, size } : upload,
        ),
      );
      return;
    }
    const uploadId = crypto.randomUUID();
    pendingApiUploadIdRef.current = uploadId;
    setUploads((previous) => [
      {
        id: uploadId,
        name: "API payload",
        size,
        type: "application/json",
        source: "API",
        status: "uploading",
        createdAt: Date.now(),
      },
      ...previous,
    ]);
  };

  const processLocalExtraction = async () => {
    const existingUploadId = pendingLocalUploadIdRef.current;
    const uploadId = existingUploadId ?? crypto.randomUUID();

    if (!localFile) {
      setExtractFeedback({
        state: "error",
        message: "Add a JSON file before extracting.",
      });
      setExtracting(false);
      return;
    }

    if (!existingUploadId) {
      pendingLocalUploadIdRef.current = uploadId;
      setUploads((previous) => [
        {
          id: uploadId,
          name: localFile.name,
          size: localFile.size,
          type: localFile.type || localFile.name.split(".").pop() || "file",
          source: "Local",
          status: "uploading",
          createdAt: Date.now(),
        },
        ...previous,
      ]);
    }

    const parsedPayload = localFileText ? safeJsonParse(localFileText) : null;
    const payloadMetadata = extractLocaleAndPageId(parsedPayload);

    try {
      const formData = new FormData();
      formData.append("file", localFile);

      const response = await fetch("/api/ingestion/upload", {
        method: "POST",
        body: formData,
      });
      const payload = await response.json();
      const details = parseBackendPayload(payload);
    const fallbackIdentifier = `file-upload:${localFile.name}`;
    const sourceIdentifier = details.sourceIdentifier ?? fallbackIdentifier;
    const sourceType = inferSourceType(details.sourceType, sourceIdentifier, "file") ?? "file";

      setUploads((previous) =>
        previous.map((item) =>
          item.id === uploadId
            ? {
                ...item,
                status: response.ok ? "success" : "error",
                cleansedId: details.cleansedId ?? item.cleansedId,
                backendStatus: details.status ?? item.backendStatus,
                backendMessage: details.message ?? item.backendMessage,
                sourceIdentifier,
                sourceType,
                locale: payloadMetadata.locale ?? details.locale ?? item.locale,
                pageId: payloadMetadata.pageId ?? details.pageId ?? item.pageId,
              }
            : item,
        ),
      );

      if (!response.ok) {
        setExtractFeedback({
          state: "error",
          message: details.message ?? "Backend rejected the upload.",
        });
        setExtracting(false);
        return;
      }

      const metadata: ExtractionContext["metadata"] = {
        name: localFile.name,
        size: localFile.size,
        source: describeSourceLabel(sourceType, "Local upload"),
        cleansedId: details.cleansedId,
        status: details.status,
        uploadedAt: Date.now(),
        sourceIdentifier,
        sourceType,
        locale: payloadMetadata.locale ?? details.locale,
        pageId: payloadMetadata.pageId ?? details.pageId,
      };
      const snapshotId = details.cleansedId ?? uploadId;
      let snapshotPersisted = false;
      let resolvedSnapshotId: string | undefined;

      if (snapshotId) {
        const result = await persistSnapshot(snapshotId, {
          mode: "local",
          metadata,
          rawJson: localFileText ?? undefined,
          tree: treeNodes,
          backendPayload: payload,
        });
        snapshotPersisted = result.ok;
        resolvedSnapshotId = result.snapshotId;
        if (!result.ok) {
          console.warn(
            "Unable to cache extraction snapshot, falling back to session storage.",
            result.message,
          );
        }
      }

      const persistenceResult = saveExtractionContext({
        mode: "local",
        metadata,
        snapshotId: snapshotPersisted ? resolvedSnapshotId ?? snapshotId : undefined,
        tree: snapshotPersisted ? undefined : treeNodes,
        rawJson: snapshotPersisted ? undefined : localFileText ?? undefined,
        backendPayload: snapshotPersisted ? undefined : payload,
      });

      if (!persistenceResult.ok) {
        setExtractFeedback({
          state: "error",
          message: describeExtractionPersistenceError(persistenceResult),
        });
        setExtracting(false);
        return;
      }

      setExtractFeedback({
        state: "success",
        message: "Extraction ready. Redirecting...",
      });
      setExtracting(false);
      router.push("/extraction");
    } finally {
      pendingLocalUploadIdRef.current = null;
    }
  };

  const processApiExtraction = async () => {
    if (!apiPayload.trim()) {
      setExtractFeedback({
        state: "error",
        message: "Paste a JSON payload before extracting.",
      });
      setExtracting(false);
      return;
    }

    const parsed = safeJsonParse(apiPayload);
    if (!parsed) {
      setExtractFeedback({
        state: "error",
        message: "Payload must be valid JSON before submission.",
      });
      setExtracting(false);
      return;
    }
    const payloadMetadata = extractLocaleAndPageId(parsed);

    setApiFeedback({ state: "loading" });
    const existingUploadId = pendingApiUploadIdRef.current;
    const uploadId = existingUploadId ?? crypto.randomUUID();
    if (!existingUploadId) {
      pendingApiUploadIdRef.current = uploadId;
      setUploads((previous) => [
        {
          id: uploadId,
          name: "API payload",
          size: apiPayload.length,
          type: "application/json",
          source: "API",
          status: "uploading",
          createdAt: Date.now(),
          locale: payloadMetadata.locale,
          pageId: payloadMetadata.pageId,
        },
        ...previous,
      ]);
    } else {
      setUploads((previous) =>
        previous.map((upload) =>
          upload.id === uploadId ? { ...upload, size: apiPayload.length } : upload,
        ),
      );
    }

    try {
      const response = await fetch("/api/ingestion/payload", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ payload: parsed }),
      });
      const payload = await response.json();
      const details = parseBackendPayload(payload);
    const fallbackIdentifier = details.cleansedId ?? `api-payload:${uploadId}`;
    const sourceIdentifier = details.sourceIdentifier ?? fallbackIdentifier;
    const sourceType = inferSourceType(details.sourceType, sourceIdentifier, "api") ?? "api";

      setUploads((previous) =>
        previous.map((upload) =>
          upload.id === uploadId
            ? {
                ...upload,
                status: response.ok ? "success" : "error",
                cleansedId: details.cleansedId ?? upload.cleansedId,
                backendStatus: details.status ?? upload.backendStatus,
                backendMessage: details.message ?? upload.backendMessage,
                sourceIdentifier,
                sourceType,
                locale: payloadMetadata.locale ?? details.locale ?? upload.locale,
                pageId: payloadMetadata.pageId ?? details.pageId ?? upload.pageId,
              }
            : upload,
        ),
      );

      setApiFeedback({
        state: response.ok ? "success" : "error",
        message: response.ok ? "Payload accepted." : "Backend rejected the payload.",
      });

      if (!response.ok) {
        setExtractFeedback({
          state: "error",
          message: details.message ?? "Backend rejected the payload.",
        });
        setExtracting(false);
        return;
      }

      const previewNodes = seedPreviewTree("API payload", parsed);

      const metadata: ExtractionContext["metadata"] = {
        name: "API payload",
        size: apiPayload.length,
        source: describeSourceLabel(sourceType, "API payload"),
        cleansedId: details.cleansedId,
        status: details.status,
        uploadedAt: Date.now(),
        sourceIdentifier,
        sourceType,
        locale: payloadMetadata.locale ?? details.locale,
        pageId: payloadMetadata.pageId ?? details.pageId,
      };
      const snapshotId = details.cleansedId ?? uploadId;
      const serializedPayload = JSON.stringify(parsed, null, 2);

      let snapshotPersisted = false;
      let resolvedSnapshotId: string | undefined;
      if (snapshotId) {
        const snapshotResult = await persistSnapshot(snapshotId, {
          mode: "api",
          metadata,
          rawJson: serializedPayload,
          tree: previewNodes,
          backendPayload: payload,
        });
        snapshotPersisted = snapshotResult.ok;
        resolvedSnapshotId = snapshotResult.snapshotId;
        if (!snapshotResult.ok) {
          console.warn(
            "Unable to cache API extraction snapshot, falling back to browser session storage.",
            snapshotResult.message,
          );
        }
      }

      const persistenceResult = saveExtractionContext({
        mode: "api",
        metadata,
        snapshotId: snapshotPersisted ? resolvedSnapshotId ?? snapshotId : undefined,
        tree: snapshotPersisted ? undefined : previewNodes,
        rawJson: snapshotPersisted ? undefined : serializedPayload,
        backendPayload: snapshotPersisted ? undefined : payload,
      });

      if (!persistenceResult.ok) {
        setExtractFeedback({
          state: "error",
          message: describeExtractionPersistenceError(persistenceResult),
        });
        setExtracting(false);
        return;
      }

      setExtractFeedback({
        state: "success",
        message: "Extraction ready. Redirecting...",
      });
      setExtracting(false);
      router.push("/extraction");
    } finally {
      pendingApiUploadIdRef.current = null;
    }
  };

  const processS3Extraction = async () => {
    const normalized = s3Uri.trim();
    if (!normalized) {
      setExtractFeedback({
        state: "error",
        message: "Provide an s3://bucket/key (or classpath:) URI first.",
      });
      setExtracting(false);
      return;
    }

    setS3Feedback({ state: "loading" });
    const uploadId = crypto.randomUUID();
    setUploads((previous) => [
      {
        id: uploadId,
        name: normalized,
        size: 0,
        type: "text/uri-list",
        source: "S3",
        status: "uploading",
        createdAt: Date.now(),
      },
      ...previous,
    ]);

    const response = await fetch("/api/ingestion/s3", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sourceUri: normalized }),
    });
    const payload = await response.json();
    const details = parseBackendPayload(payload);
    const sourceIdentifier = details.sourceIdentifier ?? normalized;
    const sourceType = inferSourceType(details.sourceType, sourceIdentifier, "s3") ?? "s3";

    setUploads((previous) =>
      previous.map((upload) =>
        upload.id === uploadId
          ? {
              ...upload,
              status: response.ok ? "success" : "error",
              cleansedId: details.cleansedId ?? upload.cleansedId,
              backendStatus:
                details.status ?? (response.ok ? "ACCEPTED" : upload.backendStatus),
              backendMessage: details.message ?? upload.backendMessage,
              sourceIdentifier,
              sourceType,
              locale: details.locale ?? upload.locale,
              pageId: details.pageId ?? upload.pageId,
            }
          : upload,
      ),
    );

    setS3Feedback({
      state: response.ok ? "success" : "error",
      message: response.ok
        ? "Source accepted."
        : "Backend rejected the S3/classpath request.",
    });

    if (!response.ok) {
      setExtractFeedback({
        state: "error",
        message: details.message ?? "Backend rejected the request.",
      });
      setExtracting(false);
      return;
    }

    const metadata: ExtractionContext["metadata"] = {
      name: normalized,
      size: 0,
      source: describeSourceLabel(sourceType, "S3 / Cloud"),
      cleansedId: details.cleansedId,
      status: details.status,
      uploadedAt: Date.now(),
      sourceIdentifier,
      sourceType,
      locale: details.locale,
      pageId: details.pageId,
    };
    const snapshotId = details.cleansedId ?? uploadId;

    let snapshotPersisted = false;
    let resolvedSnapshotId: string | undefined;
    if (snapshotId) {
      const snapshotResult = await persistSnapshot(snapshotId, {
        mode: "s3",
        metadata,
        sourceUri: normalized,
        backendPayload: payload,
      });
      snapshotPersisted = snapshotResult.ok;
      resolvedSnapshotId = snapshotResult.snapshotId;
      if (!snapshotResult.ok) {
        console.warn(
          "Unable to cache S3 extraction snapshot, falling back to session storage metadata only.",
          snapshotResult.message,
        );
      }
    }

    const persistenceResult = saveExtractionContext({
      mode: "s3",
      metadata,
      sourceUri: normalized,
      snapshotId: snapshotPersisted ? resolvedSnapshotId ?? snapshotId : undefined,
      backendPayload: snapshotPersisted ? undefined : payload,
    });

    if (!persistenceResult.ok) {
      setExtractFeedback({
        state: "error",
        message: describeExtractionPersistenceError(persistenceResult),
      });
      setExtracting(false);
      return;
    }

    setExtractFeedback({
      state: "success",
      message: "Extraction ready. Redirecting...",
    });
    setExtracting(false);
    router.push("/extraction");
  };

  const parseBackendPayload = (payload: any) => {
    const body = payload?.body;
    const rawBody = payload?.rawBody;

    const bodyRecord =
      body && typeof body === "object" && !Array.isArray(body)
        ? (body as Record<string, unknown>)
        : null;
    const metadataRecord =
      bodyRecord?.metadata && typeof bodyRecord.metadata === "object"
        ? (bodyRecord.metadata as Record<string, unknown>)
        : null;

    const cleansedId =
      pickString(bodyRecord?.cleansedDataStoreId) ??
      pickString(bodyRecord?.cleansedId) ??
      pickString(bodyRecord?.id);
    const status = pickString(bodyRecord?.status);
    const locale = pickLocale(metadataRecord) ?? pickLocale(bodyRecord);
    const pageId = pickPageId(metadataRecord) ?? pickPageId(bodyRecord);

    const pickMessage = (source: unknown) => {
      const direct = pickString(source);
      if (direct) return direct;
      if (source && typeof source === "object") {
        const candidates = [
          (source as Record<string, unknown>)["error"],
          (source as Record<string, unknown>)["message"],
          (source as Record<string, unknown>)["detail"],
          (source as Record<string, unknown>)["statusText"],
          (source as Record<string, unknown>)["description"],
        ];
        for (const candidate of candidates) {
          const value = pickString(candidate);
          if (value) return value;
        }
      }
      return undefined;
    };

    const deriveSourceIdentifier = () => {
      return (
        pickString(bodyRecord?.sourceIdentifier) ??
        pickString(bodyRecord?.sourceUri) ??
        pickString(metadataRecord?.sourceIdentifier) ??
        pickString(metadataRecord?.sourceUri) ??
        pickString(payload?.sourceIdentifier) ??
        pickString(payload?.sourceUri)
      );
    };

    const sourceIdentifier = deriveSourceIdentifier();
    const sourceType = inferSourceType(
      pickString(bodyRecord?.sourceType) ?? pickString(metadataRecord?.sourceType),
      sourceIdentifier,
    );

    const message =
      pickMessage(body) ??
      pickMessage(payload?.error) ??
      pickMessage(rawBody) ??
      (typeof rawBody === "string" ? rawBody : undefined);

    return { cleansedId, status, message, sourceIdentifier, sourceType, locale, pageId };
  };

  type SnapshotPayload = Omit<ExtractionSnapshot, "storedAt">;

  const persistSnapshot = async (
    id: string,
    payload: SnapshotPayload,
  ): Promise<{ ok: boolean; snapshotId?: string; message?: string }> => {
    if (!id) {
      return { ok: false, message: "Snapshot id is missing." };
    }

    const snapshotPayload: ExtractionSnapshot = {
      ...payload,
      storedAt: Date.now(),
    };

    try {
      const response = await fetch("/api/ingestion/context", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id,
          payload: {
            ...payload,
            mode: payload.mode,
            metadata: payload.metadata,
            rawJson: payload.rawJson,
            tree: payload.tree,
            sourceUri: payload.sourceUri,
            backendPayload: payload.backendPayload,
          },
        }),
      });

      if (!response.ok) {
        let message = "Failed to cache extraction snapshot.";
        try {
          const body = await response.json();
          if (body?.error) message = body.error;
        } catch {
          // ignore
        }
        throw new Error(message);
      }

      return { ok: true, snapshotId: id };
    } catch (error) {
      const localId = `local:${id}`;
      const localResult = await storeClientSnapshot(localId, snapshotPayload);
      if (localResult.ok) {
        return { ok: true, snapshotId: localId };
      }

      return {
        ok: false,
        message:
          localResult.message ??
          (error instanceof Error ? error.message : "Failed to cache extraction snapshot."),
      };
    }
  };

  const handleDeleteUpload = (uploadId: string) => {
    setUploads((previous) => previous.filter((upload) => upload.id !== uploadId));
  };

  const handleDownloadUpload = async (upload: UploadHistoryItem) => {
    if (!upload.cleansedId) {
      window.alert("Download is available after the backend returns a cleansed ID.");
      return;
    }
    setDownloadInFlight(upload.id);
    try {
      const response = await fetch(`/api/ingestion/resume/${encodeURIComponent(upload.cleansedId)}`, {
        method: "POST",
      });
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload?.error ?? "Backend rejected the download request.");
      }
      const normalized =
        typeof payload.body === "object" && payload.body !== null
          ? JSON.stringify(payload.body, null, 2)
          : typeof payload.rawBody === "string" && payload.rawBody.trim()
            ? payload.rawBody
            : JSON.stringify(payload, null, 2);
      const blob = new Blob([normalized], { type: "application/json" });
      const safeName = upload.name.split("/").pop()?.replace(/[^\w.-]+/g, "_") || "upload";
      const fileName = `${safeName}-${upload.cleansedId}.json`;
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(url);
    } catch (error) {
      console.error("Unable to download upload payload.", error);
      window.alert(
        error instanceof Error
          ? error.message
          : "Unable to download this upload. Please try again later.",
      );
    } finally {
      setDownloadInFlight((current) => (current === upload.id ? null : current));
    }
  };

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

      return (
        <div key={node.id} className="space-y-2">
          <div className="flex items-center gap-2 rounded-lg px-2 py-1.5">
            {hasChildren ? (
              <button
                type="button"
                onClick={() => toggleNode(node.id)}
                className="text-slate-500 transition hover:text-slate-800"
                aria-label={expanded ? "Collapse section" : "Expand section"}
              >
                {expanded ? (
                  <ChevronDownIcon className="size-4" />
                ) : (
                  <ChevronRightIcon className="size-4" />
                )}
              </button>
            ) : (
              <span className="size-4" />
            )}
            <div className="flex flex-col">
              <span className="text-sm font-medium text-slate-900">
                {node.label}
              </span>
              {!hasChildren && (
                <span className="text-xs text-slate-500">{node.path}</span>
              )}
            </div>
          </div>
          {hasChildren && expanded && (
            <div className="border-l border-slate-100 pl-4">
              {renderTree(node.children!)}
            </div>
          )}
        </div>
      );
    });

  return (
    <PipelineShell currentStep="ingestion">
      <StageHero
        title="Ingestion"
        description="Upload local JSON files, paste API payloads, or reference cloud storage to kick off the pipeline."
      />

      <main className="mx-auto flex max-w-7xl flex-col gap-6 px-6 py-8 lg:flex-row">
        {/* Left Pane: Ingestion & History */}
        <section className="flex-[1.2] space-y-6">
          <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <h2 className="text-xl font-semibold text-black">Upload Files</h2>
              </div>
              <FeedbackPill feedback={extractFeedback} />
            </div>

            <div className="mt-6 grid gap-4 sm:grid-cols-3">
              {uploadTabs.map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  disabled={tab.disabled}
                  onClick={() => !tab.disabled && setActiveTab(tab.id)}
                  className={clsx(
                    "group relative rounded-2xl border p-4 text-left transition-all",
                    tab.disabled
                      ? "border-dashed border-slate-100 opacity-40"
                      : activeTab === tab.id
                        ? "border-black bg-white ring-1 ring-black shadow-lg"
                        : "border-slate-200 hover:border-slate-400 bg-white",
                  )}
                >
                  <div className="flex flex-col gap-3">
                    <div
                      className={clsx(
                        "flex size-10 items-center justify-center rounded-xl transition-colors",
                        activeTab === tab.id ? "bg-black text-white" : "bg-slate-100 text-slate-600",
                      )}
                    >
                      <tab.icon className="size-5" />
                    </div>
                    <div>
                      <p className="text-sm font-bold text-black">{tab.title}</p>
                      <p className="mt-1 text-xs text-slate-500 leading-relaxed">
                        {tab.description}
                      </p>
                    </div>
                  </div>
                </button>
              ))}
            </div>

            {activeTab === "local" && (
              <div className="mt-6 rounded-2xl border border-dashed border-slate-300 bg-slate-50 p-8 text-center">
                <label
                  htmlFor="file-upload"
                  onDragOver={(event) => {
                    event.preventDefault();
                    event.stopPropagation();
                  }}
                  onDrop={(event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    handleLocalFileSelection(event.dataTransfer.files);
                  }}
                  className="flex cursor-pointer flex-col items-center gap-4"
                >
                  <ArrowUpTrayIcon className="size-10 text-black" />
                  <div>
                    <p className="text-sm font-semibold text-black">
                      Drag a JSON file here or <span className="underline">browse</span>
                    </p>
                    <p className="mt-1 text-xs text-slate-500">Single-file uploads only. Max 50 MB.</p>
                    {localFile && (
                      <div className="mt-4 inline-flex items-center gap-2 rounded-lg bg-white px-3 py-1.5 text-xs font-medium text-black shadow-sm border border-slate-200">
                        <DocumentTextIcon className="size-4 text-slate-400" />
                        {localFile.name}
                        <span className="text-slate-400">({formatBytes(localFile.size)})</span>
                      </div>
                    )}
                  </div>
                  <input
                    id="file-upload"
                    ref={fileInputRef}
                    type="file"
                    className="sr-only"
                    accept=".json,application/json"
                    onChange={(event) => handleLocalFileSelection(event.target.files)}
                  />
                </label>
              </div>
            )}

            {activeTab === "api" && (
              <div className="mt-6 space-y-4">
                <div className="flex items-center justify-between text-xs font-bold uppercase tracking-wider text-slate-500">
                  <span>JSON Payload</span>
                  <FeedbackPill feedback={apiFeedback} />
                </div>
                <textarea
                  value={apiPayload}
                  onChange={(event) => {
                    setApiPayload(event.target.value);
                    const parsed = safeJsonParse(event.target.value);
                    if (parsed) {
                      seedPreviewTree("API payload", parsed);
                      setApiFeedback({ state: "idle" });
                      ensurePendingApiUpload(event.target.value.length);
                    }
                  }}
                  rows={8}
                  placeholder='{ "product": { "name": "Vision Pro" } }'
                  className="w-full rounded-2xl border border-slate-200 bg-slate-50 p-4 text-sm font-mono text-black focus:border-black focus:bg-white focus:outline-none transition-all shadow-inner"
                />
              </div>
            )}

            {activeTab === "s3" && (
              <div className="mt-6 space-y-4">
                <div className="flex items-center justify-between text-xs font-bold uppercase tracking-wider text-slate-500">
                  <span>Connection Details</span>
                  <FeedbackPill feedback={s3Feedback} />
                </div>
                <div className="space-y-3">
                  <div className="grid gap-2">
                    <label className="text-[10px] font-bold uppercase text-slate-400 px-1">
                      Bucket Name
                    </label>
                    <input
                      readOnly
                      placeholder="my-data-bucket"
                      className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-2.5 text-sm text-slate-400 cursor-not-allowed"
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div className="grid gap-2">
                      <label className="text-[10px] font-bold uppercase text-slate-400 px-1">
                        Region
                      </label>
                      <input
                        readOnly
                        placeholder="us-east-1"
                        className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-2.5 text-sm text-slate-400 cursor-not-allowed"
                      />
                    </div>
                    <div className="grid gap-2">
                      <label className="text-[10px] font-bold uppercase text-slate-400 px-1">
                        Prefix (Optional)
                      </label>
                      <input
                        value={s3Uri}
                        onChange={(event) => setS3Uri(event.target.value)}
                        placeholder="data/raw/"
                        className="w-full rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm text-black focus:border-black focus:outline-none"
                      />
                    </div>
                  </div>
                  <button
                    disabled
                    className="w-full rounded-xl bg-slate-900 py-3 text-sm font-bold text-white opacity-50 cursor-not-allowed"
                  >
                    Connect to S3
                  </button>
                </div>
              </div>
            )}
          </div>

          <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
            <h3 className="text-lg font-semibold text-black">Upload History</h3>
            <div className="mt-6 space-y-3">
              {uploads.length === 0 && (
                <div className="rounded-2xl border border-dashed border-slate-100 py-12 text-center">
                  <p className="text-sm text-slate-400 font-medium">No recent activity found.</p>
                </div>
              )}
              {uploads.map((upload) => {
                const status = statusStyles[upload.status];
                const downloading = downloadInFlight === upload.id;
                return (
                  <div
                    key={upload.id}
                    className="group flex items-center justify-between gap-4 rounded-2xl border border-slate-100 bg-white p-4 transition-all hover:border-slate-200 hover:shadow-md"
                  >
                    <div className="flex items-center gap-4">
                      <div className="flex size-10 items-center justify-center rounded-xl bg-slate-50 text-slate-400 group-hover:bg-black group-hover:text-white transition-colors">
                        <DocumentTextIcon className="size-5" />
                      </div>
                      <div>
                        <p className="text-sm font-bold text-black">{upload.name}</p>
                        <p className="text-[10px] font-medium text-slate-400 uppercase tracking-wider">
                          {new Date(upload.createdAt).toLocaleDateString()} • {upload.source}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-4">
                      <span
                        className={clsx(
                          "inline-flex items-center gap-2 rounded-full px-3 py-1 text-[10px] font-bold uppercase tracking-wider",
                          status.className,
                        )}
                      >
                        <span className={clsx("size-1.5 rounded-full", status.dot)} />
                        {status.label}
                      </span>
                      <div className="flex items-center gap-1">
                        <button
                          type="button"
                          onClick={() => handleDownloadUpload(upload)}
                          disabled={downloading}
                          className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-black transition-colors"
                        >
                          <ArrowDownTrayIcon className="size-4" />
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDeleteUpload(upload.id)}
                          className="rounded-lg p-2 text-slate-400 hover:bg-slate-50 hover:text-slate-500 transition-colors"
                        >
                          <TrashIcon className="size-4" />
                        </button>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </section>

        {/* Right Pane: Select Items / Preview */}
        <section className="flex-1 rounded-3xl border border-slate-200 bg-white flex flex-col shadow-sm">
          <div className="p-6 border-b border-slate-100">
            <h3 className="text-lg font-semibold text-black">Select Items</h3>
            <div className="mt-6 grid grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <label className="text-[10px] font-bold uppercase tracking-widest text-slate-400 px-1">
                  Source
                </label>
                <div className="relative">
                  <select className="w-full appearance-none rounded-xl border border-slate-200 bg-slate-50 px-4 py-2.5 text-sm text-black focus:border-black focus:outline-none focus:bg-white transition-all">
                    <option>Amazon S3 / Cloud</option>
                    <option>Local Upload</option>
                    <option>API Endpoint</option>
                  </select>
                  <ChevronDownIcon className="pointer-events-none absolute right-4 top-1/2 size-4 -translate-y-1/2 text-slate-400" />
                </div>
              </div>
              <div className="space-y-1.5">
                <label className="text-[10px] font-bold uppercase tracking-widest text-slate-400 px-1">
                  File Type
                </label>
                <div className="relative">
                  <select className="w-full appearance-none rounded-xl border border-slate-200 bg-slate-50 px-4 py-2.5 text-sm text-black focus:border-black focus:outline-none focus:bg-white transition-all">
                    <option>Select file</option>
                    <option>JSON</option>
                    <option>PDF</option>
                  </select>
                  <ChevronDownIcon className="pointer-events-none absolute right-4 top-1/2 size-4 -translate-y-1/2 text-slate-400" />
                </div>
              </div>
            </div>

            <div className="relative mt-6">
              <MagnifyingGlassIcon className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-slate-400" />
              <input
                type="search"
                placeholder="Search..."
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                className="w-full rounded-xl border border-slate-200 bg-slate-50 py-3 pl-11 pr-4 text-sm text-black focus:border-black focus:bg-white focus:outline-none transition-all"
              />
            </div>
          </div>

          <div className="flex-1 overflow-y-auto p-6 scrollbar-thin scrollbar-thumb-slate-200 scrollbar-track-transparent">
            {filteredTree.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center text-center opacity-40">
                <InboxStackIcon className="size-12 mb-4 text-slate-300" />
                <p className="text-sm font-medium text-slate-500">
                  Select a file to view its structure
                </p>
              </div>
            ) : (
              <div className="space-y-1">{renderTree(filteredTree)}</div>
            )}
          </div>

          <div className="p-6 bg-slate-50 rounded-b-3xl border-t border-slate-200">
            <button
              type="button"
              onClick={handleExtractData}
              disabled={extracting}
              className={clsx(
                "w-full rounded-xl py-4 text-sm font-bold uppercase tracking-widest transition-all",
                extracting
                  ? "bg-slate-200 text-slate-400 cursor-not-allowed"
                  : "bg-slate-400 text-white hover:bg-black shadow-lg hover:shadow-black/20",
              )}
            >
              {extracting ? (
                <span className="flex items-center justify-center gap-3">
                  <ArrowPathIcon className="size-5 animate-spin" />
                  Extracting...
                </span>
              ) : (
                "Extract Data"
              )}
            </button>
          </div>
        </section>
      </main>
    </PipelineShell>
  );
}