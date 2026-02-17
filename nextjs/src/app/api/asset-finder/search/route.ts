import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

export async function POST(request: NextRequest) {
  if (!backendBaseUrl) {
    return NextResponse.json({ error: "SPRINGBOOT_BASE_URL is not configured." }, { status: 500 });
  }

  try {
    const body = await request.json();
    const targetUrl = new URL("/api/asset-finder/search", backendBaseUrl);
    const upstream = await fetch(targetUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const result = await upstream.json();
    return NextResponse.json(result, { status: upstream.status });
  } catch (error) {
    return NextResponse.json({ error: "Unable to reach Spring Boot endpoint." }, { status: 502 });
  }
}
