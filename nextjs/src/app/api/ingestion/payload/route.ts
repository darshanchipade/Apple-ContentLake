import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeStringify = (input: unknown) => {
  try {
    return JSON.stringify(input);
  } catch {
    return null;
  }
};

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

const normalizeMetadataValue = (value: unknown): string | null => {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : null;
};

const extractMetadata = (incoming: unknown) => {
  if (!incoming || typeof incoming !== "object") {
    return null;
  }
  const record = incoming as Record<string, unknown>;
  if (!record.metadata || typeof record.metadata !== "object") {
    return null;
  }
  const metadata = record.metadata as Record<string, unknown>;
  return {
    tenant: normalizeMetadataValue(metadata.tenant),
    environment: normalizeMetadataValue(metadata.environment),
    project: normalizeMetadataValue(metadata.project),
    site: normalizeMetadataValue(metadata.site),
    geo: normalizeMetadataValue(metadata.geo),
    locale: normalizeMetadataValue(metadata.locale),
  };
};

export async function POST(request: NextRequest) {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { error: "SPRINGBOOT_BASE_URL is not configured." },
      { status: 500 },
    );
  }

  let incoming: unknown;
  try {
    incoming = await request.json();
  } catch {
    return NextResponse.json(
      { error: "Request body must be valid JSON." },
      { status: 400 },
    );
  }

  const payload =
    typeof incoming === "object" && incoming !== null
      ? (incoming as Record<string, unknown>).payload
      : undefined;

  if (payload === undefined) {
    return NextResponse.json(
      { error: "Missing `payload` attribute." },
      { status: 400 },
    );
  }

  const serialized = safeStringify(payload);
  if (serialized === null) {
    return NextResponse.json(
      { error: "Payload could not be serialized." },
      { status: 400 },
    );
  }

  try {
    const targetUrl = new URL("/api/ingest-json-payload", backendBaseUrl);
    const metadata = extractMetadata(incoming);
    if (metadata) {
      Object.entries(metadata).forEach(([key, value]) => {
        if (value) {
          targetUrl.searchParams.set(key, value);
        }
      });
    }
    const upstream = await fetch(targetUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: serialized,
    });

    const rawBody = await upstream.text();
    const body = safeParse(rawBody);

    return NextResponse.json(
      {
        upstreamStatus: upstream.status,
        upstreamOk: upstream.ok,
        body,
        rawBody,
      },
      { status: upstream.status },
    );
  } catch (error) {
    return NextResponse.json(
      {
        error:
          error instanceof Error
            ? error.message
            : "Unable to reach Spring Boot backend.",
      },
      { status: 502 },
    );
  }
}