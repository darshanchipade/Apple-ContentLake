import { NextRequest, NextResponse } from "next/server";
import { readSnapshot, writeSnapshot } from "@/lib/server/extraction-snapshot";
import type { ExtractionSnapshot } from "@/lib/extraction-snapshot";

type SnapshotPayload = {
  id?: string;
  payload?: Partial<ExtractionSnapshot>;
};

export async function POST(request: NextRequest) {
  let body: SnapshotPayload;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Request body must be JSON." }, { status: 400 });
  }

  const { id, payload } = body;
  if (!id || typeof id !== "string") {
    return NextResponse.json({ error: "Field `id` is required." }, { status: 400 });
  }
  if (!payload || typeof payload !== "object") {
    return NextResponse.json({ error: "Field `payload` is required." }, { status: 400 });
  }

  const snapshot: ExtractionSnapshot = {
    mode: payload.mode ?? "local",
    metadata: payload.metadata ?? {
      name: "unknown",
      size: 0,
      source: "unknown",
      uploadedAt: Date.now(),
    },
    rawJson: payload.rawJson,
    tree: payload.tree,
    sourceUri: payload.sourceUri,
    backendPayload: payload.backendPayload,
    storedAt: Date.now(),
  };

  await writeSnapshot(id, snapshot);
  return NextResponse.json({ ok: true });
}

export async function GET(request: NextRequest) {
  const id = request.nextUrl.searchParams.get("id");
  if (!id) {
    return NextResponse.json(
      { error: "Missing required `id` query parameter." },
      { status: 400 },
    );
  }

  const snapshot = await readSnapshot(id);
  if (!snapshot) {
    return NextResponse.json({ error: "Snapshot not found." }, { status: 404 });
  }

  return NextResponse.json(snapshot);
}