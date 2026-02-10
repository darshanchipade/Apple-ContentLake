"use client";

export type UploadStatus = "uploading" | "success" | "error";
export type UploadSource = "Local" | "API" | "S3";

export type UploadHistoryItem = {
  id: string;
  name: string;
  size: number;
  type: string;
  source: UploadSource;
  status: UploadStatus;
  createdAt: number;
  cleansedId?: string;
  backendStatus?: string;
  backendMessage?: string;
  sourceIdentifier?: string;
  sourceType?: string;
  locale?: string;
  pageId?: string;
};

export const UPLOAD_HISTORY_STORAGE_KEY = "content-lake.upload-history.v1";
export const MAX_UPLOAD_HISTORY = 25;

const isUploadHistoryItem = (value: unknown): value is UploadHistoryItem => {
  if (!value || typeof value !== "object") return false;
  const record = value as Partial<UploadHistoryItem>;
  return (
    typeof record.id === "string" &&
    typeof record.name === "string" &&
    typeof record.source === "string" &&
    typeof record.status === "string" &&
    typeof record.createdAt === "number"
  );
};

export const sanitizeUploadHistory = (value: unknown): UploadHistoryItem[] => {
  if (!Array.isArray(value)) return [];
  return value.filter(isUploadHistoryItem).slice(0, MAX_UPLOAD_HISTORY);
};

export const readUploadHistory = (): UploadHistoryItem[] => {
  if (typeof window === "undefined") return [];
  try {
    const stored = window.localStorage.getItem(UPLOAD_HISTORY_STORAGE_KEY);
    if (!stored) return [];
    return sanitizeUploadHistory(JSON.parse(stored));
  } catch {
    return [];
  }
};

export const writeUploadHistory = (items: UploadHistoryItem[]) => {
  if (typeof window === "undefined") return;
  try {
    const trimmed = items.slice(0, MAX_UPLOAD_HISTORY);
    window.localStorage.setItem(UPLOAD_HISTORY_STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // ignore persistence failures
  }
};