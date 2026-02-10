import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

const extractSourceUri = (payload: unknown): string | null => {
  if (typeof payload === "string") {
    const trimmed = payload.trim();
    return trimmed.length ? trimmed : null;
  }

  if (typeof payload === "object" && payload !== null) {
    const candidate =
      (payload as Record<string, unknown>).sourceUri ??
      (payload as Record<string, unknown>).s3Uri;
    if (typeof candidate === "string") {
      const trimmed = candidate.trim();
      return trimmed.length ? trimmed : null;
    }
  }

  return null;
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

  const sourceUri = extractSourceUri(incoming);
  if (!sourceUri) {
    return NextResponse.json(
      { error: "Missing `sourceUri` (accepts string, sourceUri, or s3Uri)." },
      { status: 400 },
    );
  }

  const url = new URL(
    `/api/extract-cleanse-enrich-and-store?${new URLSearchParams({
      sourceUri,
    }).toString()}`,
    backendBaseUrl,
  );

  try {
    const upstream = await fetch(url, { method: "GET" });
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
