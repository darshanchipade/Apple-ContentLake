import { NextResponse } from "next/server";

const backendBaseUrl = process.env.SPRINGBOOT_BASE_URL;

type AssetFinderTile = {
  geo?: string;
  locale?: string;
  site?: string;
};

type AssetFinderSearchResponse = {
  items?: AssetFinderTile[];
};

type AssetFinderOptions = {
  tenants: string[];
  environments: string[];
  projects: string[];
  sites: string[];
  geos: string[];
  geoToLocales: Record<string, string[]>;
};

const DEFAULT_OPTIONS: AssetFinderOptions = {
  tenants: ["applecom-cms"],
  environments: ["stage", "prod", "qa"],
  projects: ["rome"],
  sites: ["ipad", "mac"],
  geos: ["WW", "JP", "KR"],
  geoToLocales: {
    WW: ["en_US"],
    JP: ["ja_JP"],
    KR: ["ko_KR"],
  },
};

const safeParse = (payload: string) => {
  try {
    return JSON.parse(payload);
  } catch {
    return payload;
  }
};

const normalizeText = (value: unknown): string | null => {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : null;
};

const normalizeGeo = (value: unknown): string | null => {
  const text = normalizeText(value);
  return text ? text.toUpperCase() : null;
};

const normalizeLocale = (value: unknown): string | null => {
  const text = normalizeText(value);
  if (!text) return null;
  const normalized = text.replace("-", "_");
  if (normalized.length === 5 && normalized[2] === "_") {
    return `${normalized.slice(0, 2).toLowerCase()}_${normalized.slice(3).toUpperCase()}`;
  }
  return normalized;
};

const parseSearchBody = (payload: unknown): AssetFinderSearchResponse => {
  if (!payload || typeof payload !== "object") {
    return {};
  }
  const root = payload as Record<string, unknown>;
  if (root.body && typeof root.body === "object") {
    return root.body as AssetFinderSearchResponse;
  }
  return root as AssetFinderSearchResponse;
};

const buildOptionsFromSearch = (payload: unknown): AssetFinderOptions | null => {
  const searchBody = parseSearchBody(payload);
  const items = Array.isArray(searchBody.items) ? searchBody.items : [];
  if (!items.length) {
    return null;
  }

  const geoToLocales = new Map<string, Set<string>>();
  const sites = new Set<string>(DEFAULT_OPTIONS.sites);

  for (const item of items) {
    const geo = normalizeGeo(item.geo);
    const locale = normalizeLocale(item.locale);
    const site = normalizeText(item.site)?.toLowerCase();

    if (site) {
      sites.add(site);
    }
    if (!geo || !locale) {
      continue;
    }
    if (!geoToLocales.has(geo)) {
      geoToLocales.set(geo, new Set<string>());
    }
    geoToLocales.get(geo)?.add(locale);
  }

  if (!geoToLocales.size) {
    return null;
  }

  const sortedGeos = Array.from(geoToLocales.keys()).sort();
  const normalizedGeoToLocales: Record<string, string[]> = {};
  for (const geo of sortedGeos) {
    normalizedGeoToLocales[geo] = Array.from(geoToLocales.get(geo) ?? []).sort();
  }

  return {
    tenants: DEFAULT_OPTIONS.tenants,
    environments: DEFAULT_OPTIONS.environments,
    projects: DEFAULT_OPTIONS.projects,
    sites: Array.from(sites).sort(),
    geos: sortedGeos,
    geoToLocales: normalizedGeoToLocales,
  };
};

const fetchBackend = async (path: string, init?: RequestInit) => {
  const targetUrl = new URL(path, backendBaseUrl);
  const upstream = await fetch(targetUrl, init);
  const rawBody = await upstream.text();
  const body = safeParse(rawBody);
  return { upstream, rawBody, body };
};

const tryFetchBackend = async (path: string, init?: RequestInit) => {
  try {
    return await fetchBackend(path, init);
  } catch {
    return null;
  }
};

export async function GET() {
  if (!backendBaseUrl) {
    return NextResponse.json(
      { error: "SPRINGBOOT_BASE_URL is not configured." },
      { status: 500 },
    );
  }

  try {
    // Try canonical endpoint first, then legacy path for compatibility.
    const canonicalAttempt = await tryFetchBackend("/api/asset-finder/options", { method: "GET" });
    if (canonicalAttempt?.upstream.ok) {
      return NextResponse.json(
        {
          upstreamStatus: canonicalAttempt.upstream.status,
          upstreamOk: true,
          body: canonicalAttempt.body,
          rawBody: canonicalAttempt.rawBody,
        },
        { status: 200 },
      );
    }

    const legacyAttempt = await tryFetchBackend("/asset-finder/options", { method: "GET" });
    if (legacyAttempt?.upstream.ok) {
        return NextResponse.json(
          {
            upstreamStatus: legacyAttempt.upstream.status,
            upstreamOk: true,
            body: legacyAttempt.body,
            rawBody: legacyAttempt.rawBody,
          },
          { status: 200 },
        );
    }

    // Final fallback: derive options from search rows to avoid hardcoded dropdowns.
    const searchAttempt = await tryFetchBackend("/api/asset-finder/search", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ page: 0, size: 500 }),
    });
    if (searchAttempt?.upstream.ok) {
      const derived = buildOptionsFromSearch(searchAttempt.body);
      if (derived) {
        return NextResponse.json(
          {
            upstreamStatus: 200,
            upstreamOk: true,
            derivedFrom: "search-fallback",
            body: derived,
            rawBody: JSON.stringify(derived),
          },
          { status: 200 },
        );
      }
    }

    return NextResponse.json(
      {
        upstreamStatus: canonicalAttempt?.upstream.status ?? legacyAttempt?.upstream.status ?? 404,
        upstreamOk: false,
        body: DEFAULT_OPTIONS,
        rawBody: JSON.stringify(DEFAULT_OPTIONS),
        derivedFrom: "static-fallback",
      },
      { status: 200 },
    );
  } catch (error) {
    return NextResponse.json(
      {
        error:
          error instanceof Error
            ? error.message
            : "Unable to reach Spring Boot asset finder options endpoint.",
      },
      { status: 502 },
    );
  }
}