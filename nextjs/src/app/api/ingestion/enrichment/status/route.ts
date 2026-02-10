import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const STATUS_PIPELINE = [
  "ENRICHMENT_TRIGGERED",
  "WAITING_FOR_RESULTS",
  "ENRICHMENT_RUNNING",
  "PARTIALLY_ENRICHED",
  "ENRICHMENT_COMPLETE",
];

const STATUS_ALIAS: Record<string, string> = {
  CLEANSED_PENDING_ENRICHMENT: "ENRICHMENT_TRIGGERED",
  QUEUED_FOR_ENRICHMENT: "ENRICHMENT_TRIGGERED",
  ENRICHMENT_QUEUED: "ENRICHMENT_TRIGGERED",
  WAITING_FOR_AI_OUTPUT: "WAITING_FOR_RESULTS",
  AWAITING_AI_OUTPUT: "WAITING_FOR_RESULTS",
  WAITING_FOR_RESULTS: "WAITING_FOR_RESULTS",
  ENRICHMENT_IN_PROGRESS: "ENRICHMENT_RUNNING",
  ENRICHMENT_PROCESSING: "ENRICHMENT_RUNNING",
  AI_ENRICHMENT_IN_PROGRESS: "ENRICHMENT_RUNNING",
  ENRICHMENT_STARTED: "ENRICHMENT_RUNNING",
  ENRICHMENT_DONE: "ENRICHMENT_COMPLETE",
  ENRICHED: "ENRICHMENT_COMPLETE",
  COMPLETED: "ENRICHMENT_COMPLETE",
};

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === "object" && value !== null;
};

const pickString = (value: unknown): string | undefined => {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : undefined;
};

const pickNumber = (value: unknown): number | undefined => {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string") {
    const numeric = Number.parseFloat(value);
    if (!Number.isNaN(numeric) && Number.isFinite(numeric)) {
      return numeric;
    }
  }
  return undefined;
};

const normalizeStatusValue = (value: unknown): string | null => {
  const raw =
    pickString(value) ??
    (isRecord(value) ? pickString(value.status) ?? pickString(value.currentStatus) ?? pickString(value.pipelineStatus) : undefined);
  if (!raw) return null;
  const normalized = raw.replace(/\s+/g, "_").replace(/-+/g, "_").toUpperCase();
  if (STATUS_ALIAS[normalized]) {
    return STATUS_ALIAS[normalized];
  }
  if (STATUS_PIPELINE.includes(normalized) || normalized === "ERROR") {
    return normalized;
  }
  return null;
};

const sanitizeHistory = (
  historyCandidate: unknown,
  startedAt: number,
): { status: string; timestamp: number }[] => {
  if (!Array.isArray(historyCandidate)) return [];
  const seen = new Set<string>();
  const sanitized: { status: string; timestamp: number }[] = [];

  for (const entry of historyCandidate) {
    if (!isRecord(entry)) continue;
    const status = normalizeStatusValue(entry.status);
    if (!status || seen.has(status)) continue;
    const timestamp = pickNumber(entry.timestamp) ?? startedAt;
    sanitized.push({ status, timestamp });
    seen.add(status);
  }

  return sanitized;
};

const deriveHistory = (
  existingHistory: { status: string; timestamp: number }[],
  normalizedStatus: string | null,
  startedAt: number,
): { status: string; timestamp: number }[] => {
  const normalizedIndex = normalizedStatus ? STATUS_PIPELINE.indexOf(normalizedStatus) : -1;
  const derived: { status: string; timestamp: number }[] = [...existingHistory];

  if (normalizedStatus) {
    const alreadyPresent = derived.some((entry) => entry.status === normalizedStatus);
    if (!alreadyPresent) {
      derived.push({ status: normalizedStatus, timestamp: Date.now() });
    }
  }

  if (!derived.length) {
    const baseline = Number.isFinite(startedAt) ? startedAt : Date.now();
    const statusesToInclude = normalizedIndex >= 0 ? STATUS_PIPELINE.slice(0, normalizedIndex + 1) : [STATUS_PIPELINE[0], STATUS_PIPELINE[1]];
    statusesToInclude.forEach((status, index) => {
      derived.push({ status, timestamp: baseline + index * 60_000 });
    });
    if (normalizedStatus === "ERROR") {
      derived.push({ status: "ERROR", timestamp: baseline + statusesToInclude.length * 60_000 });
    }
  } else {
    derived.sort((a, b) => a.timestamp - b.timestamp);
  }

  if (normalizedStatus === "ERROR" && !derived.some((entry) => entry.status === "ERROR")) {
    derived.push({ status: "ERROR", timestamp: Date.now() });
  }

  const ordered: { status: string; timestamp: number }[] = [];
  const seen = new Set<string>();
  for (const status of [...STATUS_PIPELINE, "ERROR"]) {
    const entry = derived.find((candidate) => candidate.status === status);
    if (entry && !seen.has(entry.status)) {
      ordered.push(entry);
      seen.add(entry.status);
    }
  }
  for (const entry of derived) {
    if (!seen.has(entry.status)) {
      ordered.push(entry);
      seen.add(entry.status);
    }
  }

  return ordered.length
    ? ordered
    : [
        { status: "ENRICHMENT_TRIGGERED", timestamp: startedAt || Date.now() },
        { status: "WAITING_FOR_RESULTS", timestamp: (startedAt || Date.now()) + 60_000 },
      ];
};

export async function GET(request: NextRequest) {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { error: "SPRINGBOOT_BASE_URL is not configured." },
      { status: 500 },
    );
  }

  const id = request.nextUrl.searchParams.get("id");
  if (!id) {
    return NextResponse.json(
      { error: "Missing required `id` query parameter." },
      { status: 400 },
    );
  }

  const contextUrl = new URL(`/api/cleansed-context/${id}`, backendBaseUrl);
  const statusUrl = new URL(`/api/cleansed-data-status/${id}`, backendBaseUrl);

  try {
    const [contextResponse, statusResponse] = await Promise.all([
      fetch(contextUrl),
      fetch(statusUrl),
    ]);

    const rawContextBody = await contextResponse.text();
    const contextBody = safeParse(rawContextBody);
    const rawStatusBody = await statusResponse.text();
    const normalizedStatus = statusResponse.ok ? normalizeStatusValue(rawStatusBody) : null;

    if (!contextResponse.ok) {
      const fallbackHistory = deriveHistory([], normalizedStatus, Date.now());
      return NextResponse.json(
        {
          upstream: {
            context: { status: contextResponse.status, ok: contextResponse.ok },
            pipeline: { status: statusResponse.status, ok: statusResponse.ok },
          },
          body: {
            statusHistory: fallbackHistory,
            latestStatus: normalizedStatus,
            startedAt: Date.now(),
          },
          rawBody: rawContextBody,
          pipelineRawBody: rawStatusBody,
        },
        { status: statusResponse.ok ? 200 : statusResponse.status },
      );
    }

    const contextRecord = isRecord(contextBody) ? (contextBody as Record<string, unknown>) : {};
    const startedAtCandidate = pickNumber(contextRecord.startedAt) ?? pickNumber((contextRecord.metadata as Record<string, unknown>)?.startedAt) ?? Date.now();
    const historyCandidate = sanitizeHistory(contextRecord.statusHistory, startedAtCandidate);
    const statusHistory = deriveHistory(historyCandidate, normalizedStatus, startedAtCandidate);

    const responseBody: Record<string, unknown> = {
      ...contextRecord,
      statusHistory,
      latestStatus: normalizedStatus,
      startedAt: startedAtCandidate,
    };

    return NextResponse.json(
      {
        upstream: {
          context: { status: contextResponse.status, ok: contextResponse.ok },
          pipeline: { status: statusResponse.status, ok: statusResponse.ok },
        },
        body: responseBody,
        rawBody: rawContextBody,
        pipelineRawBody: rawStatusBody,
      },
      { status: 200 },
    );
  } catch (error) {
    return NextResponse.json(
      {
        error:
          error instanceof Error
            ? error.message
            : "Unable to reach enrichment status backing services.",
      },
      { status: 502 },
    );
  }
}