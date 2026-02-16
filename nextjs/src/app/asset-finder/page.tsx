"use client";

import { useEffect, useMemo, useState } from "react";
import clsx from "clsx";
import {
  ArrowPathIcon,
  InformationCircleIcon,
  MagnifyingGlassIcon,
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
    tenant: options.tenants[0] ?? "applecom-cms",
    environment: options.environments[0] ?? "stage",
    project: options.projects[0] ?? "rome",
    site: options.sites[0] ?? "ipad",
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
  if (path.startsWith("/")) return path;
  return `/${path}`;
};

function TilePreview({ path, label }: { path?: string; label: string }) {
  const [loadFailed, setLoadFailed] = useState(false);
  const normalized = normalizeAssetPath(path);
  if (!normalized || loadFailed) {
    return (
      <div className="flex h-44 items-center justify-center bg-slate-100 text-slate-500">
        <PhotoIcon className="size-16" />
      </div>
    );
  }
  return (
    <img
      src={normalized}
      alt={label}
      className="h-44 w-full object-contain bg-slate-100"
      loading="lazy"
      onError={() => setLoadFailed(true)}
    />
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

  const localesForGeo = useMemo(() => {
    return options.geoToLocales?.[filters.geo] ?? [];
  }, [options, filters.geo]);

  useEffect(() => {
    let active = true;
    const loadOptions = async () => {
      setLoadingOptions(true);
      setError(null);
      try {
        const response = await fetch("/api/asset-finder/options");
        const payload = await response.json();
        if (!response.ok) {
          throw new Error(payload?.error ?? "Unable to load Asset Finder options.");
        }
        const parsed = normalizeUpstreamBody<AssetFinderOptions>(payload) ?? DEFAULT_OPTIONS;
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
          size: 120,
        }),
      });
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload?.error ?? "Asset Finder query failed.");
      }
      const parsed = normalizeUpstreamBody<AssetFinderSearchResponse>(payload);
      setResults(parsed ?? { count: 0, page: 0, size: 120, totalPages: 0, items: [] });
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
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload?.error ?? "Unable to load asset metadata.");
      }
      const parsed = normalizeUpstreamBody<AssetFinderDetail>(payload);
      setDetail(parsed);
    } catch (loadError) {
      setDetail(null);
      setDetailError(loadError instanceof Error ? loadError.message : "Unable to load asset metadata.");
    } finally {
      setIsDetailLoading(false);
    }
  };

  return (
    <PipelineShell currentStep="ingestion" showTracker={false}>
      <main className="mx-auto max-w-[1700px] p-4 lg:p-8">
        <div className="mb-6 flex items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl lg:text-3xl font-bold text-slate-900">Asset Finder</h1>
            <p className="text-sm text-slate-500">
              Find icons and image assets from uploaded JSON metadata.
            </p>
          </div>
          <div className="inline-flex items-center gap-2 rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-600">
            <MagnifyingGlassIcon className="size-4" />
            Content Lake
          </div>
        </div>

        <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm lg:p-5">
          <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
            <label className="text-xs font-semibold text-slate-600">
              Tenant
              <select
                value={filters.tenant}
                onChange={(event) => setFilters((prev) => ({ ...prev, tenant: event.target.value }))}
                className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2 py-2 text-sm text-slate-900"
              >
                {options.tenants.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-xs font-semibold text-slate-600">
              Environment
              <select
                value={filters.environment}
                onChange={(event) => setFilters((prev) => ({ ...prev, environment: event.target.value }))}
                className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2 py-2 text-sm text-slate-900"
              >
                {options.environments.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-xs font-semibold text-slate-600">
              Project
              <select
                value={filters.project}
                onChange={(event) => setFilters((prev) => ({ ...prev, project: event.target.value }))}
                className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2 py-2 text-sm text-slate-900"
              >
                {options.projects.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-xs font-semibold text-slate-600">
              Site / Page
              <select
                value={filters.site}
                onChange={(event) => setFilters((prev) => ({ ...prev, site: event.target.value }))}
                className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2 py-2 text-sm text-slate-900"
              >
                {options.sites.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-xs font-semibold text-slate-600">
              Geo / Region
              <select
                value={filters.geo}
                onChange={(event) => {
                  const nextGeo = event.target.value;
                  const nextLocale = options.geoToLocales[nextGeo]?.[0] ?? "";
                  setFilters((prev) => ({ ...prev, geo: nextGeo, locale: nextLocale || prev.locale }));
                }}
                className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2 py-2 text-sm text-slate-900"
              >
                {options.geos.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-xs font-semibold text-slate-600">
              Locale
              <select
                value={filters.locale}
                onChange={(event) => setFilters((prev) => ({ ...prev, locale: event.target.value }))}
                className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2 py-2 text-sm text-slate-900"
              >
                {(localesForGeo.length ? localesForGeo : [filters.locale]).map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={runFilter}
              disabled={isFiltering || loadingOptions}
              className={clsx(
                "rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-blue-700",
                (isFiltering || loadingOptions) && "cursor-wait opacity-70",
              )}
            >
              {isFiltering ? "Filtering..." : "Filter"}
            </button>
            <button
              type="button"
              onClick={resetFilters}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-blue-700"
            >
              Reset
            </button>
            {loadingOptions && (
              <span className="inline-flex items-center gap-2 text-xs text-slate-500">
                <ArrowPathIcon className="size-4 animate-spin" />
                Loading options...
              </span>
            )}
          </div>
        </section>

        <section className="mt-6">
          <div className="mb-3 text-sm font-semibold text-slate-700">
            Count: {results?.count ?? 0}
          </div>
          {error && (
            <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
              {error}
            </div>
          )}
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            {(results?.items ?? []).map((tile) => (
              <article key={tile.id} className="rounded-xl border border-slate-200 bg-white shadow-sm">
                <TilePreview
                  path={tile.previewUri ?? tile.interactivePath}
                  label={tile.altText ?? tile.assetKey ?? "Asset preview"}
                />
                <div className="p-3">
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <p className="text-sm font-semibold text-slate-900 truncate">
                      {tile.assetKey ?? "asset"}
                    </p>
                    <button
                      type="button"
                      onClick={() => openDetail(tile.id)}
                      className="rounded-full p-1 text-slate-500 transition hover:bg-slate-100 hover:text-slate-900"
                      title="View metadata"
                    >
                      <InformationCircleIcon className="size-5" />
                    </button>
                  </div>
                  <p className="text-xs text-slate-500 break-all">
                    Interactive Path:{" "}
                    {tile.interactivePath ? (
                      <a
                        href={normalizeAssetPath(tile.interactivePath)}
                        className="text-blue-700 underline"
                        target="_blank"
                        rel="noreferrer"
                      >
                        {tile.interactivePath}
                      </a>
                    ) : (
                      "—"
                    )}
                  </p>
                </div>
              </article>
            ))}
          </div>
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
                          const thumbnail = normalizeAssetPath(pathCandidate);
                          return (
                            <tr key={viewport} className="border-t border-slate-100 align-top">
                              <td className="px-3 py-2 font-medium text-slate-800">{viewport}</td>
                              <td className="px-3 py-2">
                                {pathCandidate ? (
                                  <a
                                    href={thumbnail}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="break-all text-blue-700 underline"
                                  >
                                    {pathCandidate}
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
