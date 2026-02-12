"use client";

import {
  ArrowDownTrayIcon,
  DocumentTextIcon,
  InboxStackIcon,
  TrashIcon,
} from "@heroicons/react/24/outline";
import clsx from "clsx";
import { useEffect, useMemo, useState } from "react";
import { PipelineShell } from "@/components/PipelineShell";
import {
  readUploadHistory,
  writeUploadHistory,
  type UploadHistoryItem,
} from "@/lib/upload-history";
import { formatBytes } from "@/lib/format";

const statusStyles = {
  uploading: {
    label: "Uploading",
    className: "bg-amber-50 text-amber-700",
    dot: "bg-amber-400",
  },
  success: {
    label: "Accepted",
    className: "bg-primary-soft text-primary",
    dot: "bg-primary",
  },
  error: {
    label: "Error",
    className: "bg-rose-50 text-rose-700",
    dot: "bg-rose-500",
  },
} satisfies Record<
  UploadHistoryItem["status"],
  { label: string; className: string; dot: string }
>;

export default function UploadActivityPage() {
  const [uploads, setUploads] = useState<UploadHistoryItem[]>([]);
  const [activeUploadId, setActiveUploadId] = useState<string | null>(null);
  const [downloadInFlight, setDownloadInFlight] = useState<string | null>(null);
  const [historyHydrated, setHistoryHydrated] = useState(false);

  useEffect(() => {
    const history = readUploadHistory();
    setUploads(history);
    setActiveUploadId((current) => current ?? history[0]?.id ?? null);
    setHistoryHydrated(true);
  }, []);

  useEffect(() => {
    if (!historyHydrated) return;
    writeUploadHistory(uploads);
  }, [uploads, historyHydrated]);

  const activeUpload = useMemo(
    () => uploads.find((upload) => upload.id === activeUploadId) ?? null,
    [uploads, activeUploadId],
  );

  useEffect(() => {
    if (activeUploadId) return;
    if (uploads.length) {
      setActiveUploadId(uploads[0].id);
    }
  }, [uploads, activeUploadId]);

  const handleDeleteUpload = (uploadId: string) => {
    setUploads((previous) => {
      const next = previous.filter((upload) => upload.id !== uploadId);
      if (next.length === 0) {
        setActiveUploadId(null);
      } else if (activeUploadId === uploadId) {
        setActiveUploadId(next[0].id);
      }
      return next;
    });
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

  return (
    <PipelineShell currentStep="ingestion">
      <div className="p-4 lg:p-8 max-w-6xl mx-auto">
        <div className="mb-6 lg:mb-8">
          <h1 className="text-2xl lg:text-3xl font-bold text-black">Activity</h1>
          <p className="text-xs sm:text-sm font-medium text-slate-500 mt-1">
            Review and manage your recently processed files.
          </p>
        </div>

      <main className="mx-auto grid gap-6 lg:gap-8 lg:grid-cols-[1fr_400px] items-start">
        <section className="rounded-3xl border border-slate-200 bg-white shadow-sm flex flex-col h-[500px] lg:h-auto lg:min-h-[600px] overflow-hidden">
          <div className="p-5 lg:p-6 border-b border-slate-100 flex items-center justify-between gap-3">
            <div>
              <h2 className="text-lg font-bold text-gray-900">Upload History</h2>
              <p className="text-xs text-gray-500">Recently processed files and datasets</p>
            </div>
            <div className="flex items-center gap-2 rounded-full bg-gray-50 px-3 py-1 text-xs font-bold text-gray-600 border border-gray-100">
              <InboxStackIcon className="size-4 text-gray-400" />
              {uploads.length} tracked
            </div>
          </div>

          <div className="p-4 lg:p-6 space-y-4 overflow-y-auto custom-scrollbar flex-1">
            {uploads.length === 0 && (
              <div className="rounded-2xl border border-dashed border-slate-200 py-10 text-center text-sm text-slate-500">
                Uploads will appear here once you submit files from the ingestion screen.
              </div>
            )}
            {uploads.map((upload) => {
              const status = statusStyles[upload.status];
              const downloading = downloadInFlight === upload.id;
              const isActive = activeUploadId === upload.id;
              return (
                <div
                  key={upload.id}
                  className={clsx(
                    "rounded-2xl border px-4 py-3 transition",
                    isActive
                      ? "border-primary bg-primary/[0.04] shadow-[0_18px_35px_rgba(22,163,74,0.08)]"
                      : "border-slate-100 bg-slate-50 hover:border-slate-300",
                  )}
                >
                  <button
                    type="button"
                    onClick={() => setActiveUploadId(upload.id)}
                    className="flex w-full flex-wrap items-center justify-between gap-4 text-left"
                  >
                    <div className="flex items-center gap-3">
                      <div className="rounded-2xl bg-white p-2 shadow-sm">
                        <DocumentTextIcon className="size-5 text-slate-500" />
                      </div>
                      <div>
                        <p className="text-sm font-semibold text-slate-900">{upload.name}</p>
                        <p className="text-xs text-slate-500">
                          {new Date(upload.createdAt).toLocaleString()} • {upload.source}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <span
                        className={clsx(
                          "inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold",
                          status.className,
                        )}
                      >
                        <span className={clsx("size-2 rounded-full", status.dot)} />
                        {status.label}
                      </span>
                    </div>
                  </button>
                  <div className="mt-3 flex items-center justify-end gap-2 border-t border-slate-100 pt-3">
                    <button
                      type="button"
                      onClick={() => handleDownloadUpload(upload)}
                      disabled={downloading}
                      className={clsx(
                        "inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold text-slate-900 transition hover:bg-primary/10",
                        downloading && "cursor-wait opacity-60",
                      )}
                    >
                      <ArrowDownTrayIcon className="size-4" />
                      Download
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDeleteUpload(upload.id)}
                      className="inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold text-slate-900 transition hover:bg-primary/10"
                    >
                      <TrashIcon className="size-4" />
                      Delete
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        <section className="rounded-3xl border border-slate-200 bg-white p-6 lg:p-8 shadow-sm lg:sticky lg:top-24">
          <div className="flex flex-col gap-1 mb-6 lg:mb-8">
              <h3 className="text-lg lg:text-xl font-bold text-slate-900">
                {activeUpload ? "File Metadata" : "Details"}
              </h3>
              {activeUpload && (
                <p className="text-xs font-bold text-primary uppercase tracking-widest">
                  {activeUpload.name}
                </p>
              )}
          </div>

          {activeUpload ? (
            <div className="mt-5 space-y-5">
              <dl className="grid gap-4 sm:grid-cols-2">
                <div>
                  <dt className="text-xs uppercase tracking-wide text-slate-400">Uploaded</dt>
                  <dd className="text-sm font-semibold text-slate-900">
                    {new Date(activeUpload.createdAt).toLocaleString()}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs uppercase tracking-wide text-slate-400">Size</dt>
                  <dd className="text-sm font-semibold text-slate-900">
                    {formatBytes(activeUpload.size)}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs uppercase tracking-wide text-slate-400">Source type</dt>
                  <dd className="text-sm font-semibold text-slate-900">
                    {activeUpload.sourceType ?? activeUpload.source}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs uppercase tracking-wide text-slate-400">
                    Source identifier
                  </dt>
                  <dd className="text-sm font-semibold text-slate-900 break-all">
                    {activeUpload.sourceIdentifier ?? "—"}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs uppercase tracking-wide text-slate-400">Cleansed ID</dt>
                  <dd className="text-sm font-semibold text-slate-900">
                    {activeUpload.cleansedId ?? "—"}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs uppercase tracking-wide text-slate-400">Locale</dt>
                  <dd className="text-sm font-semibold text-slate-900">
                    {activeUpload.locale ?? "—"}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs uppercase tracking-wide text-slate-400">Page ID</dt>
                  <dd className="text-sm font-semibold text-slate-900">
                    {activeUpload.pageId ?? "—"}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs uppercase tracking-wide text-slate-400">Backend status</dt>
                  <dd className="text-sm font-semibold text-slate-900 break-all">
                    {activeUpload.backendStatus ?? "—"}
                  </dd>
                </div>
              </dl>
            </div>
          ) : (
            <div className="mt-10 rounded-2xl border border-dashed border-slate-200 py-10 text-center text-sm text-slate-500">
              Select an upload from the list to inspect its metadata.
            </div>
          )}
        </section>
      </main>
      </div>
    </PipelineShell>
  );
}