import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

const forward = async (request: NextRequest, targetUrl: URL, method: "PUT" | "POST") => {
  const body = await request.text();
  const upstream = await fetch(targetUrl, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body.length ? body : undefined,
  });
  const rawBody = await upstream.text();
  const parsed = safeParse(rawBody);

  return NextResponse.json(
    {
      upstreamStatus: upstream.status,
      upstreamOk: upstream.ok,
      body: parsed,
      rawBody,
    },
    { status: upstream.ok ? 200 : upstream.status },
  );
};

export async function PUT(
  request: NextRequest,
  context: { params: Promise<{ id: string }> },
) {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { error: "SPRINGBOOT_BASE_URL is not configured." },
      { status: 500 },
    );
  }
  const { id } = await context.params;
  if (!id) {
    return NextResponse.json(
      { error: "Missing enriched content element id." },
      { status: 400 },
    );
  }

  try {
    const targetUrl = new URL(`/api/enrichment/content/${id}`, backendBaseUrl);
    return await forward(request, targetUrl, "PUT");
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

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ id: string }> },
) {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { error: "SPRINGBOOT_BASE_URL is not configured." },
      { status: 500 },
    );
  }
  const { id } = await context.params;
  if (!id) {
    return NextResponse.json(
      { error: "Missing enriched content element id." },
      { status: 400 },
    );
  }

  try {
    const targetUrl = new URL(`/api/enrichment/content/${id}/generate`, backendBaseUrl);
    return await forward(request, targetUrl, "POST");
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