import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

const ensureBaseUrl = () => {
  if (!backendBaseUrl) {
    throw NextResponse.json(
      { error: "SPRINGBOOT_BASE_URL is not configured." },
      { status: 500 },
    );
  }
};

const proxyResponse = async (target: URL, init?: RequestInit) => {
  const upstream = await fetch(target, init);
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
};

export async function GET(request: NextRequest) {
  try {
    ensureBaseUrl();
  } catch (response) {
    return response as NextResponse;
  }

  try {
    const targetUrl = new URL("/api/search", backendBaseUrl);
    targetUrl.search = request.nextUrl.search;
    return await proxyResponse(targetUrl, { method: "GET" });
  } catch (error) {
    return NextResponse.json(
      {
        error:
          error instanceof Error
            ? error.message
            : "Unable to reach Spring Boot search endpoint.",
      },
      { status: 502 },
    );
  }
}

export async function POST(request: NextRequest) {
  try {
    ensureBaseUrl();
  } catch (response) {
    return response as NextResponse;
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Request body must be valid JSON." }, { status: 400 });
  }

  try {
    const targetUrl = new URL("/api/search", backendBaseUrl);
    return await proxyResponse(targetUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch (error) {
    return NextResponse.json(
      {
        error:
          error instanceof Error
            ? error.message
            : "Unable to reach Spring Boot search endpoint.",
      },
      { status: 502 },
    );
  }
}