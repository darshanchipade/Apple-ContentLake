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

  const id = request.nextUrl.searchParams.get("id");
  if (!id) {
    return NextResponse.json(
      { error: "Missing required `id` query parameter." },
      { status: 400 },
    );
  }

  const url = new URL(`/api/cleansed-data-status/${id}`, backendBaseUrl);

  try {
    const upstream = await fetch(url);
    const rawBody = await upstream.text();
    const body = safeParse(rawBody);
    const statusValue =
      typeof body === "string" ? body : typeof rawBody === "string" ? rawBody : null;

    return NextResponse.json(
      {
        upstreamStatus: upstream.status,
        upstreamOk: upstream.ok,
        status: statusValue,
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
            : "Unable to reach Spring Boot backend.",
      },
      { status: 502 },
    );
  }
}
