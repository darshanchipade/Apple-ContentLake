import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ id: string; revisionId: string }> },
) {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { error: "SPRINGBOOT_BASE_URL is not configured." },
      { status: 500 },
    );
  }
  const { id, revisionId } = await context.params;
  if (!id || !revisionId) {
    return NextResponse.json(
      { error: "Missing enriched content element id or revision id." },
      { status: 400 },
    );
  }

  try {
    const targetUrl = new URL(
      `/api/enrichment/content/${id}/restore/${revisionId}`,
      backendBaseUrl,
    );
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
      { status: upstream.ok ? 200 : upstream.status },
    );
  } catch (error) {
    return NextResponse.json(
      {
        error:
          error instanceof Error
            ? error.message
            : "Unable to reach Spring Boot enrichment endpoint.",
      },
      { status: 502 },
    );
  }
}