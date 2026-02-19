"use client";

import { useEffect, useMemo, useState } from "react";
import clsx from "clsx";
import {
  ArrowPathIcon,
  InformationCircleIcon,
  PhotoIcon,
  XMarkIcon,
} from "@heroicons/react/24/outline";
import { PipelineShell } from "@/components/PipelineShell";

type AssetFinderOptions = {
  tenants: string[];
  environments: string[];
  projects: string[];
  sites: string[];
  geos: string[];
  geoToLocales: Record<string, string[]>;
};

type AssetFinderFilters = {
  tenant: string;
  environment: string;
  project: string;
  site: string;
  geo: string;
  locale: string;
};

type AssetFinderTile = {
  id: string;
  assetKey?: string;
  assetModel?: string;
  sectionPath?: string;
  sectionUri?: string;
  interactivePath?: string;
  previewUri?: string;
  locale?: string;
  site?: string;
  geo?: string;
  altText?: string;
};

type AssetFinderSearchResponse = {
  count: number;
  page: number;
  size: number;
  totalPages: number;
  items: AssetFinderTile[];
};

type AssetFinderDetail = {
  id: string;
  tenant?: string;
  environment?: string;
  project?: string;
  site?: string;
  geo?: string;
  locale?: string;
  assetKey?: string;
  assetModel?: string;
  sectionPath?: string;
  sectionUri?: string;
  assetNodePath?: string;
  interactivePath?: string;
  previewUri?: string;
  altText?: string;
  accessibilityText?: string;
  viewports?: Record<string, Record<string, unknown>>;
  metadata?: Record<string, unknown>;
};

const ALL_VALUE = "";

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

const buildInitialFilters = (options: AssetFinderOptions): AssetFinderFilters => {
  const geo = options.geos[0] ?? "WW";
  const locale = options.geoToLocales?.[geo]?.[0] ?? "en_US";
  return {
    tenant: ALL_VALUE,
    environment: ALL_VALUE,
    project: ALL_VALUE,
    site: ALL_VALUE,
    geo,
    locale,
  };
};

const normalizeUpstreamBody = <T,>(payload: unknown): T | null => {
  if (!payload || typeof payload !== "object") {
    return null;
  }
  const record = payload as Record<string, unknown>;
  if (record.body && typeof record.body === "object") {
    return record.body as T;
  }
  return record as T;
};

const normalizeAssetPath = (path: string | undefined) => {
  if (!path) return undefined;
  if (path.startsWith("http://") || path.startsWith("https://")) return path;
  if (path.startsWith("/")) return `http://www.apple.com${path}`;
  return `http://www.apple.com/${path}`;
};

const normalizeLocaleValue = (locale: string | undefined) => {
  if (!locale) return undefined;
  const normalized = locale.trim().replace("-", "_");
  if (normalized.length === 5 && normalized[2] === "_") {
    return `${normalized.slice(0, 2).toLowerCase()}_${normalized.slice(3).toUpperCase()}`;
  }
  return normalized;
};

const UI_COLORS = {
  pageBg: "#ffffff",
  panelBg: "#ffffff",
  panelBorder: "#e2e8f0",
  label: "#475569",
  inputBorder: "#e2e8f0",
  button: "#0071e3",
  buttonBorder: "#0071e3",
  tileBg: "#ffffff",
  tilePreviewBg: "#f8fafc",
  tileBorder: "#e2e8f0",
  pathText: "#475569",
  link: "#1d4ed8",
  count: "#334155",
};

const FILTER_PANEL_SHADOW = "0 1px 3px rgba(15, 23, 42, 0.08)";
const BUTTON_SHADOW = "0 1px 2px rgba(15, 23, 42, 0.18)";
const TILE_SHADOW = "0 2px 7px rgba(15, 23, 42, 0.16)";
const SEARCH_PAGE_SIZE = 200;

const FILTER_HELP_TEXT = {
  tenant: "Content source tenant (for example: applecom-cms).",
  environment: "Deployment environment for this content (stage, prod, qa).",
  project: "Project or campaign grouping for uploaded content.",
  site: "Site or page family (for example: ipad, mac).",
  geo: "Business geo/region grouping used for filtering.",
  locale: "Locale code for language + country (for example: en_CA).",
} as const;

const safeParsePayload = (raw: string): unknown => {
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
};

const parseProxyPayload = async <T,>(response: Response): Promise<T | null> => {
  const raw = await response.text();
  const parsed = safeParsePayload(raw);
  return normalizeUpstreamBody<T>(parsed);
};

function TilePreview({ path, label }: { path?: string; label: string }) {
  const [loadFailed, setLoadFailed] = useState(false);
  const normalized = normalizeAssetPath(path);
  if (!normalized || loadFailed) {
    return (
      <div className="flex aspect-square w-full items-center justify-center text-slate-500" style={{ backgroundColor: UI_COLORS.tilePreviewBg }}>
        <PhotoIcon className="size-16" />
      </div>
    );
  }
  return (
    <img
      src={normalized}
      alt={label}
      className="aspect-square w-full object-contain p-6"
      style={{ backgroundColor: UI_COLORS.tilePreviewBg }}
      loading="lazy"
      onError={() => setLoadFailed(true)}
    />
  );
}

function DropdownHelp({ text }: { text: string }) {
  return (
    <span
      title={text}
      aria-label={text}
      className="inline-flex size-3.5 cursor-help items-center justify-center rounded-full border border-slate-300 bg-white text-[9px] font-bold leading-none text-slate-500"
    >
      i
    </span>
  );
}

export default function AssetFinderPage() {
  const [options, setOptions] = useState<AssetFinderOptions>(DEFAULT_OPTIONS);
  const [filters, setFilters] = useState<AssetFinderFilters>(buildInitialFilters(DEFAULT_OPTIONS));
  const [results, setResults] = useState<AssetFinderSearchResponse | null>(null);
  const [isFiltering, setIsFiltering] = useState(false);
  const [loadingOptions, setLoadingOptions] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<AssetFinderDetail | null>(null);
  const [isDetailLoading, setIsDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [showLocaleSpecificAssets, setShowLocaleSpecificAssets] = useState(false);
  const [shareCopied, setShareCopied] = useState(false);
  const filterLabelClass = "mb-1 inline-flex items-center gap-1 text-xs font-semibold";
  const filterSelectClass =
    "h-10 w-full rounded-2xl border bg-slate-50 px-3 text-sm text-slate-900 shadow-sm focus:border-slate-900/40 focus:bg-white focus:outline-none focus:ring-2 focus:ring-slate-900/10";
  const actionButtonClass =
    "h-10 self-end rounded-full border px-5 text-sm font-semibold text-white transition hover:bg-accent";

  const localesForGeo = useMemo(() => {
    return options.geoToLocales?.[filters.geo] ?? [];
  }, [options, filters.geo]);

  const visibleItems = useMemo(() => {
    const items = results?.items ?? [];
    if (!showLocaleSpecificAssets) return items;
    const selectedLocale = normalizeLocaleValue(filters.locale);
    if (!selectedLocale) return items;
    return items.filter((item) => normalizeLocaleValue(item.locale) === selectedLocale);
  }, [results, showLocaleSpecificAssets, filters.locale]);

  useEffect(() => {
    let active = true;
    const loadOptions = async () => {
      setLoadingOptions(true);
      setError(null);
      try {
        const response = await fetch("/api/asset-finder/options");
        const payload = await parseProxyPayload<AssetFinderOptions>(response);
        if (!response.ok) {
          throw new Error("Unable to load Asset Finder options.");
        }
        const parsed = payload ?? DEFAULT_OPTIONS;
        if (!active) return;
        setOptions({
          tenants: parsed.tenants?.length ? parsed.tenants : DEFAULT_OPTIONS.tenants,
          environments: parsed.environments?.length ? parsed.environments : DEFAULT_OPTIONS.environments,
          projects: parsed.projects?.length ? parsed.projects : DEFAULT_OPTIONS.projects,
          sites: parsed.sites?.length ? parsed.sites : DEFAULT_OPTIONS.sites,
          geos: parsed.geos?.length ? parsed.geos : DEFAULT_OPTIONS.geos,
          geoToLocales:
            parsed.geoToLocales && Object.keys(parsed.geoToLocales).length
              ? parsed.geoToLocales
              : DEFAULT_OPTIONS.geoToLocales,
        });
        setFilters(buildInitialFilters(parsed));
      } catch (loadError) {
        if (!active) return;
        setError(loadError instanceof Error ? loadError.message : "Unable to load filter options.");
        setOptions(DEFAULT_OPTIONS);
        setFilters(buildInitialFilters(DEFAULT_OPTIONS));
      } finally {
        if (active) {
          setLoadingOptions(false);
        }
      }
    };

    loadOptions();
    return () => {
      active = false;
    };
  }, []);

  const runFilter = async () => {
    setIsFiltering(true);
    setError(null);
    try {
      const response = await fetch("/api/asset-finder/search", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...filters,
          page: 0,
          size: SEARCH_PAGE_SIZE,
        }),
      });
      const payload = await parseProxyPayload<AssetFinderSearchResponse>(response);
      if (!response.ok) {
        throw new Error("Asset Finder query failed.");
      }
      setResults(payload ?? { count: 0, page: 0, size: SEARCH_PAGE_SIZE, totalPages: 0, items: [] });
    } catch (filterError) {
      setResults(null);
      setError(filterError instanceof Error ? filterError.message : "Asset Finder query failed.");
    } finally {
      setIsFiltering(false);
    }
  };

  const resetFilters = () => {
    setFilters(buildInitialFilters(options));
    setResults(null);
    setError(null);
  };

  const openDetail = async (assetId: string) => {
    setIsDetailLoading(true);
    setDetailError(null);
    try {
      const response = await fetch(`/api/asset-finder/assets/${encodeURIComponent(assetId)}`);
      const payload = await parseProxyPayload<AssetFinderDetail>(response);
      if (!response.ok) {
        throw new Error("Unable to load asset metadata.");
      }
      setDetail(payload);
    } catch (loadError) {
      setDetail(null);
      setDetailError(loadError instanceof Error ? loadError.message : "Unable to load asset metadata.");
    } finally {
      setIsDetailLoading(false);
    }
  };

  const shareUrl = async () => {
    if (typeof window === "undefined") return;
    const url = new URL(window.location.href);
    Object.entries(filters).forEach(([key, value]) => {
      if (value?.trim()) {
        url.searchParams.set(key, value);
      } else {
        url.searchParams.delete(key);
      }
    });
    url.searchParams.set("localeSpecific", showLocaleSpecificAssets ? "true" : "false");
    try {
      await navigator.clipboard.writeText(url.toString());
      setShareCopied(true);
      window.setTimeout(() => setShareCopied(false), 1800);
    } catch {
      setShareCopied(false);
    }
  };

  return (
    <PipelineShell currentStep="ingestion" showTracker={false}>
      <main
        className="mx-auto max-w-[1750px] p-3 lg:p-5"
        style={{
          backgroundColor: UI_COLORS.pageBg,
        }}
      >
        <section
          className="rounded-2xl border p-4"
          style={{
            backgroundColor: UI_COLORS.panelBg,
            borderColor: UI_COLORS.panelBorder,
            boxShadow: FILTER_PANEL_SHADOW,
          }}
        >
          <div className="grid gap-2.5 md:grid-cols-3 xl:grid-cols-[repeat(6,minmax(0,1fr))_auto_auto_auto]">
            <label className="block">
              <span className={filterLabelClass} style={{ color: UI_COLORS.label }}>
                Tenant <DropdownHelp text={FILTER_HELP_TEXT.tenant} />
              </span>
              <select
                value={filters.tenant}
                onChange={(event) => setFilters((prev) => ({ ...prev, tenant: event.target.value }))}
                className={filterSelectClass}
                style={{ borderColor: UI_COLORS.inputBorder, borderRadius: "9999px" }}
              >
                <option value={ALL_VALUE}>All</option>
                {options.tenants.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className={filterLabelClass} style={{ color: UI_COLORS.label }}>
                Environment <DropdownHelp text={FILTER_HELP_TEXT.environment} />
              </span>
              <select
                value={filters.environment}
                onChange={(event) => setFilters((prev) => ({ ...prev, environment: event.target.value }))}
                className={filterSelectClass}
                style={{ borderColor: UI_COLORS.inputBorder, borderRadius: "9999px" }}
              >
                <option value={ALL_VALUE}>All</option>
                {options.environments.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className={filterLabelClass} style={{ color: UI_COLORS.label }}>
                Project <DropdownHelp text={FILTER_HELP_TEXT.project} />
              </span>
              <select
                value={filters.project}
                onChange={(event) => setFilters((prev) => ({ ...prev, project: event.target.value }))}
                className={filterSelectClass}
                style={{ borderColor: UI_COLORS.inputBorder, borderRadius: "9999px" }}
              >
                <option value={ALL_VALUE}>All</option>
                {options.projects.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className={filterLabelClass} style={{ color: UI_COLORS.label }}>
                Site / Page <DropdownHelp text={FILTER_HELP_TEXT.site} />
              </span>
              <select
                value={filters.site}
                onChange={(event) => setFilters((prev) => ({ ...prev, site: event.target.value }))}
                className={filterSelectClass}
                style={{ borderColor: UI_COLORS.inputBorder, borderRadius: "9999px" }}
              >
                <option value={ALL_VALUE}>All</option>
                {options.sites.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className={filterLabelClass} style={{ color: UI_COLORS.label }}>
                Geo / Region <DropdownHelp text={FILTER_HELP_TEXT.geo} />
              </span>
              <select
                value={filters.geo}
                onChange={(event) => {
                  const nextGeo = event.target.value;
                  const nextLocale = options.geoToLocales[nextGeo]?.[0] ?? "";
                  setFilters((prev) => ({ ...prev, geo: nextGeo, locale: nextLocale || prev.locale }));
                }}
                className={filterSelectClass}
                style={{ borderColor: UI_COLORS.inputBorder, borderRadius: "9999px" }}
              >
                {options.geos.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className={filterLabelClass} style={{ color: UI_COLORS.label }}>
                Locale <DropdownHelp text={FILTER_HELP_TEXT.locale} />
              </span>
              <select
                value={filters.locale}
                onChange={(event) => setFilters((prev) => ({ ...prev, locale: event.target.value }))}
                className={filterSelectClass}
                style={{ borderColor: UI_COLORS.inputBorder, borderRadius: "9999px" }}
              >
                {(localesForGeo.length ? localesForGeo : [filters.locale]).map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>

            <button
              type="button"
              onClick={runFilter}
              disabled={isFiltering || loadingOptions}
              className={clsx(
                actionButtonClass,
                (isFiltering || loadingOptions) && "cursor-wait opacity-70",
              )}
              style={{ backgroundColor: UI_COLORS.button, borderColor: UI_COLORS.buttonBorder, boxShadow: BUTTON_SHADOW }}
            >
              {isFiltering ? "Filtering..." : "Filter"}
            </button>
            <button
              type="button"
              onClick={resetFilters}
              className={actionButtonClass}
              style={{ backgroundColor: UI_COLORS.button, borderColor: UI_COLORS.buttonBorder, boxShadow: BUTTON_SHADOW }}
            >
              Reset
            </button>
            <button
              type="button"
              onClick={shareUrl}
              className={actionButtonClass}
              style={{ backgroundColor: UI_COLORS.button, borderColor: UI_COLORS.buttonBorder, boxShadow: BUTTON_SHADOW }}
            >
              {shareCopied ? "Copied" : "Share URL"}
            </button>
          </div>

          {loadingOptions && (
            <div className="mt-2 inline-flex items-center gap-2 text-[11px]" style={{ color: "#666666" }}>
              <ArrowPathIcon className="size-3.5 animate-spin" />
              Loading options...
            </div>
          )}
        </section>

        <section className="mt-3">
          <div className="mb-3 flex items-center justify-between gap-3">
            <p className="text-sm font-medium" style={{ color: UI_COLORS.count }}>
              Count : {visibleItems.length}/{results?.count ?? visibleItems.length}
            </p>
            <label className="inline-flex items-center gap-2 text-sm" style={{ color: UI_COLORS.count }}>
              <input
                type="checkbox"
                checked={showLocaleSpecificAssets}
                onChange={(event) => setShowLocaleSpecificAssets(event.target.checked)}
                className="h-3.5 w-3.5 rounded border text-[#2f75d6] focus:ring-[#2f75d6]"
                style={{ borderColor: "#999999" }}
              />
              Show Locale Specific Assets
            </label>
          </div>

          {error && (
            <div className="mb-4 rounded border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
              {error}
            </div>
          )}

          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {visibleItems.map((tile) => (
              <article
                key={tile.id}
                className="overflow-hidden rounded-2xl border bg-white"
                style={{
                  borderColor: UI_COLORS.tileBorder,
                  backgroundColor: UI_COLORS.tileBg,
                  boxShadow: TILE_SHADOW,
                }}
              >
                <div className="px-3 pt-3">
                  <TilePreview
                    path={tile.previewUri ?? tile.interactivePath}
                    label={tile.altText ?? tile.assetKey ?? "Asset preview"}
                  />
                </div>
                <div className="border-t px-3 py-2" style={{ borderColor: UI_COLORS.tileBorder }}>
                  <div className="flex items-end justify-between gap-2">
                    <p className="max-h-10 overflow-hidden break-all text-[10px] leading-[1.35]" style={{ color: UI_COLORS.pathText }}>
                      Interactive Path:{" "}
                      {normalizeAssetPath(tile.interactivePath) ? (
                        <a
                          href={normalizeAssetPath(tile.interactivePath)}
                          className="underline"
                          style={{ color: UI_COLORS.link }}
                          target="_blank"
                          rel="noreferrer"
                        >
                          {normalizeAssetPath(tile.interactivePath)}
                        </a>
                      ) : (
                        "—"
                      )}
                    </p>
                    <button
                      type="button"
                      onClick={() => openDetail(tile.id)}
                      className="shrink-0 rounded-full p-0.5 transition hover:bg-slate-300/50"
                      style={{ color: "#787878" }}
                      title="View metadata"
                    >
                      <InformationCircleIcon className="size-4" />
                    </button>
                  </div>
                </div>
              </article>
            ))}
          </div>

          {!isFiltering && visibleItems.length === 0 && (
            <div className="mt-6 rounded border border-dashed border-slate-300 bg-white/70 px-4 py-8 text-center text-sm text-slate-500">
              No assets available for the selected filters.
            </div>
          )}
        </section>
      </main>

      {(detail || isDetailLoading || detailError) && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/45 px-4">
          <div className="w-full max-w-4xl rounded-2xl bg-white shadow-2xl">
            <div className="flex items-center justify-between rounded-t-2xl bg-black px-5 py-3 text-white">
              <h2 className="text-lg font-semibold">Metadata Info</h2>
              <button
                type="button"
                onClick={() => {
                  setDetail(null);
                  setDetailError(null);
                }}
                className="rounded-full p-1 transition hover:bg-white/10"
              >
                <XMarkIcon className="size-6" />
              </button>
            </div>

            <div className="max-h-[70vh] overflow-auto p-5">
              {isDetailLoading && (
                <div className="inline-flex items-center gap-2 text-sm text-slate-600">
                  <ArrowPathIcon className="size-4 animate-spin" />
                  Loading metadata...
                </div>
              )}
              {detailError && (
                <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
                  {detailError}
                </div>
              )}
              {detail && (
                <div className="space-y-5">
                  <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-3">
                    {[
                      ["Tenant", detail.tenant],
                      ["Environment", detail.environment],
                      ["Project", detail.project],
                      ["Site", detail.site],
                      ["Geo", detail.geo],
                      ["Locale", detail.locale],
                      ["Asset Key", detail.assetKey],
                      ["Asset Model", detail.assetModel],
                    ].map(([label, value]) => (
                      <div key={label} className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                        <dt className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                          {label}
                        </dt>
                        <dd className="mt-1 text-slate-900 break-all">{value || "—"}</dd>
                      </div>
                    ))}
                  </dl>

                  <div className="rounded-lg border border-slate-200">
                    <table className="min-w-full text-sm">
                      <thead className="bg-slate-100 text-left text-xs uppercase tracking-wide text-slate-600">
                        <tr>
                          <th className="px-3 py-2">Viewport</th>
                          <th className="px-3 py-2">Interactive Path</th>
                          <th className="px-3 py-2">Image Thumbnail</th>
                        </tr>
                      </thead>
                      <tbody>
                        {Object.entries(detail.viewports ?? {}).map(([viewport, viewportValue]) => {
                          const pathCandidate =
                            (viewportValue?.uri as string | undefined) ??
                            (viewportValue?.uri1x as string | undefined) ??
                            (viewportValue?.uri2x as string | undefined) ??
                            (viewportValue?._uri_path as string | undefined) ??
                            (viewportValue?._uri1x_path as string | undefined);
                          const interactivePath = normalizeAssetPath(pathCandidate);
                          const thumbnail = interactivePath;
                          return (
                            <tr key={viewport} className="border-t border-slate-100 align-top">
                              <td className="px-3 py-2 font-medium text-slate-800">{viewport}</td>
                              <td className="px-3 py-2">
                                {interactivePath ? (
                                  <a
                                    href={interactivePath}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="break-all text-blue-700 underline"
                                  >
                                    {interactivePath}
                                  </a>
                                ) : (
                                  "—"
                                )}
                              </td>
                              <td className="px-3 py-2">
                                {thumbnail ? (
                                  <img
                                    src={thumbnail}
                                    alt={`${viewport} thumbnail`}
                                    className="h-20 w-16 rounded border border-slate-200 object-cover bg-slate-50"
                                  />
                                ) : (
                                  <div className="flex h-20 w-16 items-center justify-center rounded border border-slate-200 bg-slate-50">
                                    <PhotoIcon className="size-5 text-slate-400" />
                                  </div>
                                )}
                              </td>
                            </tr>
                          );
                        })}
                        {Object.keys(detail.viewports ?? {}).length === 0 && (
                          <tr>
                            <td className="px-3 py-4 text-slate-500" colSpan={3}>
                              No viewport information available for this asset.
                            </td>
                          </tr>
                        )}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </PipelineShell>
  );
}
