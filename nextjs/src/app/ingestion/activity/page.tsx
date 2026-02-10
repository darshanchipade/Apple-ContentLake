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
    <PipelineShell currentStep="ingestion" breadcrumbExtra="Upload Activity">
      <StageHero
        title="Upload activity"
        description="Review previous uploads, download payloads, and inspect metadata captured during ingestion."
      />

      <main className="mx-auto flex max-w-[1600px] flex-col gap-6 px-8 py-8 lg:flex-row">
        <section className="flex-[1.2] rounded-3xl border border-slate-200 bg-white p-6 shadow-sm flex flex-col">
          <div className="flex items-center justify-between gap-3 mb-6">
            <div>
              <h2 className="text-xl font-bold text-black uppercase tracking-tight">Recent Files</h2>
              <p className="mt-1 text-xs font-medium text-slate-400">
                {uploads.length} files tracked in history
              </p>
            </div>
          </div>

          <div className="space-y-3 flex-1 overflow-auto pr-2 scrollbar-thin scrollbar-thumb-slate-200">
            {uploads.length === 0 && (
              <div className="rounded-2xl border border-dashed border-slate-100 py-20 text-center opacity-40">
                <p className="text-sm font-medium text-slate-500 uppercase tracking-widest">No activity found</p>
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
                    "group relative rounded-2xl border p-4 transition-all",
                    isActive
                      ? "border-black bg-white ring-1 ring-black shadow-lg"
                      : "border-slate-100 bg-slate-50 hover:border-slate-300",
                  )}
                >
                  <button
                    type="button"
                    onClick={() => setActiveUploadId(upload.id)}
                    className="flex w-full items-center justify-between gap-4 text-left"
                  >
                    <div className="flex items-center gap-4">
                      <div className={clsx(
                        "flex size-10 items-center justify-center rounded-xl transition-colors",
                        isActive ? "bg-black text-white" : "bg-white text-slate-400 group-hover:bg-black group-hover:text-white"
                      )}>
                        <DocumentTextIcon className="size-5" />
                      </div>
                      <div>
                        <p className="text-sm font-bold text-black truncate max-w-[200px]">{upload.name}</p>
                        <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">
                          {new Date(upload.createdAt).toLocaleDateString()} • {upload.source}
                        </p>
                      </div>
                    </div>
                    <span
                      className={clsx(
                        "inline-flex items-center gap-2 rounded-full px-3 py-1 text-[10px] font-bold uppercase tracking-wider",
                        status.className,
                      )}
                    >
                      <span className={clsx("size-1.5 rounded-full", status.dot)} />
                      {status.label}
                    </span>
                  </button>

                  <div className="mt-4 flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button
                      type="button"
                      onClick={() => handleDownloadUpload(upload)}
                      disabled={downloading}
                      className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-black transition-all"
                      title="Download"
                    >
                      <ArrowDownTrayIcon className="size-4" />
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDeleteUpload(upload.id)}
                      className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-500 transition-all"
                      title="Delete"
                    >
                      <TrashIcon className="size-4" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        <section className="flex-1 rounded-3xl border border-slate-200 bg-white p-8 shadow-sm">
          <div className="flex items-center justify-between mb-8 pb-6 border-b border-slate-100">
            <div>
              <h3 className="text-lg font-bold text-black uppercase tracking-tight">
                File Metadata
              </h3>
              <p className="mt-1 text-xs font-medium text-slate-400 truncate max-w-[300px]">
                {activeUpload ? activeUpload.name : "Select an upload"}
              </p>
            </div>
          </div>

          {activeUpload ? (
            <div className="space-y-6">
              {[
                { label: "Status", value: activeUpload.backendStatus ?? "COMPLETED" },
                { label: "Locale", value: activeUpload.locale ?? "—" },
                { label: "Page ID", value: activeUpload.pageId ?? "—" },
                { label: "Source Type", value: activeUpload.sourceType ?? activeUpload.source },
                { label: "Cleansed ID", value: activeUpload.cleansedId ?? "—" },
                { label: "Uploaded", value: new Date(activeUpload.createdAt).toLocaleString() },
                { label: "File Size", value: formatBytes(activeUpload.size) },
              ].map(item => (
                <div key={item.label} className="group">
                  <dt className="text-[10px] font-bold uppercase tracking-widest text-slate-400 group-hover:text-black transition-colors">{item.label}</dt>
                  <dd className="mt-1.5 text-sm font-bold text-black break-all">{item.value}</dd>
                </div>
              ))}

              {activeUpload.backendMessage && (
                <div className="mt-8 rounded-2xl bg-slate-50 p-6 border border-slate-100">
                  <p className="text-[10px] font-bold uppercase tracking-widest text-slate-400 mb-2">Backend Message</p>
                  <p className="text-xs text-slate-600 leading-relaxed font-medium">{activeUpload.backendMessage}</p>
                </div>
              )}
            </div>
          ) : (
            <div className="py-20 text-center opacity-40">
              <InboxStackIcon className="size-10 mx-auto mb-4 text-slate-300" />
              <p className="text-sm font-medium text-slate-400 uppercase tracking-widest">Select an item to view details</p>
            </div>
          )}
        </section>
      </main>
    </PipelineShell>
  );
}