import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

export async function GET(request: NextRequest) {
    if (!backendBaseUrl) {
        return NextResponse.json(
            { error: "SPRINGBOOT_BASE_URL is not configured." },
            { status: 500 },
        );
    }

    try {
        const url = new URL(request.url);
        const username = url.searchParams.get("username");

        let targetPath = "/api/ingestion/history";
        if (username) {
            targetPath += `?username=${encodeURIComponent(username)}`;
        }

        const targetUrl = new URL(targetPath, backendBaseUrl);

        const upstream = await fetch(targetUrl, {
            method: "GET",
            headers: {
                "Accept": "application/json"
            },
            cache: "no-store"
        });

        const rawBody = await upstream.text();

        let body;
        try {
            body = JSON.parse(rawBody);
        } catch {
            body = rawBody;
        }

        // We return the parsed body directly as an array so the React frontend 
        // can instantly cast it to UploadHistoryItem[] without unpacking nested objects.
        return NextResponse.json(body, { status: upstream.status });

    } catch (error) {
        return NextResponse.json(
            {
                error:
                    error instanceof Error
                        ? error.message
                        : "Unable to reach Spring Boot history endpoint.",
            },
            { status: 502 },
        );
    }
}
