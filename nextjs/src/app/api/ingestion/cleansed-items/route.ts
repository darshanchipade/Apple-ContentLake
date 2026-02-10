import { NextRequest, NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

type NormalizedRow = {
  id: string;
  field: string;
  original?: string | null;
  cleansed?: string | null;
};

const pickString = (value: unknown): string | undefined => {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : undefined;
};

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === "object" && value !== null;
};

const pickFromSources = (
  sources: Array<Record<string, unknown>>,
  keys: string[],
  forbiddenValues?: string[],
) => {
  for (const key of keys) {
    if (!key) continue;
    for (const source of sources) {
      const candidate = pickString(source[key]);
      if (!candidate) continue;
      if (
        forbiddenValues?.some(
          (forbiddenValue) => forbiddenValue && candidate.trim() === forbiddenValue.trim(),
        )
      ) {
        continue;
      }
      return candidate;
    }
  }
  return undefined;
};

const normalizeItems = (payload: unknown): NormalizedRow[] => {
  const extractRawItems = (): unknown[] => {
    if (Array.isArray(payload)) return payload;
    if (isRecord(payload)) {
      if (Array.isArray(payload.items)) return payload.items as unknown[];
      if (Array.isArray(payload.records)) return payload.records as unknown[];
      if (Array.isArray(payload.data)) return payload.data as unknown[];
    }
    return [];
  };

  const FIELD_KEYS = [
    "field",
    "label",
    "key",
    "name",
    "originalFieldName",
    "fieldName",
    "itemType",
  ];
  const ORIGINAL_KEYS = [
    "originalValue",
    "rawValue",
    "sourceValue",
    "before",
    "input",
    "valueBefore",
    "value",
    "copy",
    "text",
    "content",
  ];
  const CLEANSED_KEYS = [
    "cleansedValue",
    "cleanedValue",
    "normalizedValue",
    "after",
    "output",
    "valueAfter",
    "value",
    "cleansedContent",
    "cleansedCopy",
  ];

  return extractRawItems().reduce<NormalizedRow[]>((rows, item, index) => {
    if (!isRecord(item)) {
      return rows;
    }
    const context = isRecord(item.context) ? item.context : undefined;
    const facets = context && isRecord(context.facets) ? (context.facets as Record<string, unknown>) : undefined;

    const sources: Array<Record<string, unknown>> = [item];
    if (context) sources.push(context);
    if (facets) sources.push(facets);

    const field = pickFromSources(sources, FIELD_KEYS) ?? `Item ${index + 1}`;
    const original = pickFromSources(sources, ORIGINAL_KEYS, field ? [field] : undefined) ?? null;
    const cleansed = pickFromSources(sources, CLEANSED_KEYS, field ? [field] : undefined) ?? null;

    rows.push({
      id: pickString(item.id) ?? pickString(item.contentHash) ?? `row-${index}`,
      field,
      original,
      cleansed,
    });
    return rows;
  }, []);
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

  try {
    const targetUrl = new URL(`/api/cleansed-items/${id}`, backendBaseUrl);
    const upstream = await fetch(targetUrl);
    const rawBody = await upstream.text();
    const body = safeParse(rawBody);
    const passthroughItems =
      isRecord(body) && Array.isArray(body.items)
        ? (body.items as NormalizedRow[])
        : null;
    const items = passthroughItems ?? normalizeItems(body);

    return NextResponse.json(
      {
        upstreamStatus: upstream.status,
        upstreamOk: upstream.ok,
        items,
        rawBody,
      },
      { status: upstream.ok ? 200 : upstream.status },
    );
  } catch (error) {
    return NextResponse.json(
      {
        error:
          error instanceof Error ? error.message : "Unable to reach Spring Boot backend.",
      },
      { status: 502 },
    );
  }
}