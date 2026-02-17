import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

export async function GET() {
  if (!backendBaseUrl) {
    return NextResponse.json({ error: "SPRINGBOOT_BASE_URL is not configured." }, { status: 500 });
  }

  try {
    const targetUrl = new URL("/api/asset-finder/options", backendBaseUrl);
    const upstream = await fetch(targetUrl, { method: "GET" });
    const body = await upstream.json();
    return NextResponse.json(body, { status: upstream.status });
  } catch (error) {
    return NextResponse.json({ error: "Unable to reach Spring Boot endpoint." }, { status: 502 });
  }
}
