export const pickString = (value: unknown): string | undefined => {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : undefined;
};

export const inferSourceType = (
  explicitType?: string,
  identifier?: string,
  fallback?: string,
): string | undefined => {
  const normalized = explicitType?.toLowerCase();
  if (normalized) {
    return normalized;
  }
  if (!identifier) {
    return fallback;
  }
  const token = identifier.toLowerCase();
  if (token.startsWith("file-upload") || token.startsWith("local:")) {
    return "file";
  }
  if (token.startsWith("api-payload") || token.includes("api")) {
    return "api";
  }
  if (token.startsWith("s3://") || token.includes("s3")) {
    return "s3";
  }
  if (token.startsWith("classpath:")) {
    return "classpath";
  }
  return fallback;
};

export const describeSourceLabel = (
  type?: string,
  fallback = "Unknown source",
): string => {
  if (!type) return fallback;
  const normalized = type.toLowerCase();
  if (normalized.includes("api")) return "API payload";
  if (normalized.includes("s3") || normalized.includes("cloud")) return "S3 / Cloud";
  if (normalized.includes("class")) return "Classpath resource";
  if (normalized.includes("local")) return "Local upload";
  if (normalized.includes("file")) return "File upload";
  return type;
};