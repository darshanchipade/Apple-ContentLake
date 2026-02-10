import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

const extractId = (payload: unknown): string | null => {
  if (typeof payload === "string") {
    return payload.trim().length ? payload.trim() : null;
  }
  if (typeof payload === "object" && payload !== null) {
    const candidate =
      (payload as Record<string, unknown>).id ??
      (payload as Record<string, unknown>).cleansedDataStoreId ??
      (payload as Record<string, unknown>).cleansedId;
    if (typeof candidate === "string" && candidate.trim().length) {
      return candidate.trim();
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

  const cleansedId = extractId(incoming);
  if (!cleansedId) {
    return NextResponse.json(
      { error: "Missing `id`/`cleansedDataStoreId`/`cleansedId` attribute." },
      { status: 400 },
    );
  }

  try {
    const targetUrl = new URL(`/api/enrichment/start/${cleansedId}`, backendBaseUrl);
    const upstream = await fetch(targetUrl, { method: "POST" });
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
