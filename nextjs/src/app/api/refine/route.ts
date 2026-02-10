import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

export async function GET(request: NextRequest) {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { error: "SPRINGBOOT_BASE_URL is not configured." },
      { status: 500 },
    );
  }

  try {
    const targetUrl = new URL("/api/refine", backendBaseUrl);
    targetUrl.search = request.nextUrl.search;
    const upstream = await fetch(targetUrl.toString(), { method: "GET" });
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
            : "Unable to reach Spring Boot refine endpoint.",
      },
      { status: 502 },
    );
  }
}