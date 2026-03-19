// Deprecated. LocalStorage has been deleted in favor of IngestionJob API Tracking.
// Retaining types for the UI
export type UploadStatus = "uploading" | "success" | "error";
export type UploadSource = "Local" | "API" | "S3" | "HTML";

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
