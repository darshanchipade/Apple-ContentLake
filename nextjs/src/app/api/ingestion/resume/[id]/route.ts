import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

type RouteContext = {
  params: Promise<{
    id?: string;
  }>;
};

export async function POST(_request: NextRequest, context: RouteContext) {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { error: "SPRINGBOOT_BASE_URL is not configured." },
      { status: 500 },
    );
  }

  const params = await context.params;
  const cleansedId = params.id?.trim();
  if (!cleansedId) {
    return NextResponse.json(
      { error: "Missing cleansed id in the request path." },
      { status: 400 },
    );
  }

  try {
    const targetUrl = new URL(`/api/ingestion/resume/${encodeURIComponent(cleansedId)}`, backendBaseUrl);
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
            : "Unable to reach Spring Boot resume endpoint.",
      },
      { status: 502 },
    );
  }
}