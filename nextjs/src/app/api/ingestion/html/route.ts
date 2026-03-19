import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeParse = (payload: string) => {
    try {
        return JSON.parse(payload);
    } catch {
        return payload;
    }
};

export async function POST(request: NextRequest) {
    if (!backendBaseUrl) {
        return NextResponse.json(
            { error: "SPRINGBOOT_BASE_URL is not configured." },
            { status: 500 },
        );
    }

    let incoming: any;
    try {
        incoming = await request.json();
    } catch {
        return NextResponse.json(
            { error: "Request body must be valid JSON." },
            { status: 400 },
        );
    }

    const urlPayload = incoming.url;
    if (!urlPayload || typeof urlPayload !== "string") {
        return NextResponse.json(
            { error: "Missing or invalid `url` parameter." },
            { status: 400 },
        );
    }

    const endpoint = new URL("/api/v1/ingest/unstructured/url", backendBaseUrl);

    try {
        const upstream = await fetch(endpoint.toString(), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ url: urlPayload }),
        });

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
