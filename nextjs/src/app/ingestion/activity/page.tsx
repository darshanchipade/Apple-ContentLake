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
import { StageHero } from "@/components/StageHero";
import {
  readUploadHistory,
  writeUploadHistory,
  type UploadHistoryItem,
} from "@/lib/upload-history";

const statusStyles = {
  uploading: {
    label: "Uploading",
    className: "bg-amber-50 text-amber-700",
    dot: "bg-amber-400",
  },
  success: {
    label: "Accepted",
    className: "bg-emerald-50 text-emerald-700",
    dot: "bg-emerald-500",
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

const formatBytes = (bytes: number) => {
  if (!Number.isFinite(bytes)) return "—";
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.floor(Math.log(bytes) / Math.log(1024));
  const value = bytes / Math.pow(1024, index);
  return `${value.toFixed(value > 9 || index === 0 ? 0 : 1)} ${units[index]}`;
};

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
      <StageHero
        title="Upload activity"
        description="Review previous uploads, download payloads, and inspect metadata captured during ingestion."
      />

      <main className="mx-auto grid max-w-6xl gap-6 px-6 py-10 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="flex items-center justify-between gap-3">
            <div>
              <p className="text-xs uppercase tracking-wide text-slate-400">Upload history</p>
              <h2 className="text-lg font-semibold text-slate-900">Recent files</h2>
            </div>
            <div className="flex items-center gap-2 rounded-full bg-slate-50 px-3 py-1 text-xs text-slate-600">
              <InboxStackIcon className="size-4 text-slate-500" />
              {uploads.length
                ? `${Math.min(uploads.length, 25)} tracked`
                : "No uploads tracked yet"}
            </div>
          </div>

          <div className="mt-6 space-y-4">
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
                      ? "border-slate-900 bg-slate-900/[0.04] shadow-[0_18px_35px_rgba(15,23,42,0.08)]"
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
                        "inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold text-slate-900 transition hover:bg-slate-900/10",
                        downloading && "cursor-wait opacity-60",
                      )}
                    >
                      <ArrowDownTrayIcon className="size-4" />
                      Download
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDeleteUpload(upload.id)}
                      className="inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold text-slate-900 transition hover:bg-slate-900/10"
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

        <section className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs uppercase tracking-wide text-slate-400">File metadata</p>
              <h3 className="text-lg font-semibold text-slate-900">
                {activeUpload ? activeUpload.name : "Select an upload"}
              </h3>
            </div>
            {activeUpload && (
              <span className="text-xs font-semibold uppercase tracking-[0.25em] text-slate-400">
                {activeUpload.source}
              </span>
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
                  <dd className="text-sm font-semibold text-slate-900">
                    {activeUpload.backendStatus ?? "—"}
                  </dd>
                </div>
              </dl>
              {activeUpload.backendMessage && (
                <div className="rounded-2xl border border-slate-100 bg-slate-50 p-4 text-sm text-slate-700">
                  <p className="text-xs uppercase tracking-wide text-slate-400">Backend message</p>
                  <p className="mt-1">{activeUpload.backendMessage}</p>
                </div>
              )}
            </div>
          ) : (
            <div className="mt-10 rounded-2xl border border-dashed border-slate-200 py-10 text-center text-sm text-slate-500">
              Select an upload from the list to inspect its metadata.
            </div>
          )}
        </section>
      </main>
    </PipelineShell>
  );
}