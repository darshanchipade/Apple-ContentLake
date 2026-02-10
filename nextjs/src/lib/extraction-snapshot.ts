import { TreeNode } from "./tree";

export type ExtractionSnapshot = {
  mode: "local" | "api" | "s3";
  metadata: {
    name: string;
    size: number;
    source: string;
    cleansedId?: string;
    status?: string;
    uploadedAt: number;
    locale?: string;
    pageId?: string;
  };
  rawJson?: string;
  tree?: TreeNode[];
  sourceUri?: string;
  backendPayload?: unknown;
  storedAt: number;
};
