"use client";

import {
  AdjustmentsHorizontalIcon,
  ArrowPathIcon,
  InformationCircleIcon,
  MagnifyingGlassIcon,
  PhotoIcon,
  XMarkIcon,
} from "@heroicons/react/24/outline";
import clsx from "clsx";
import { useEffect, useState } from "react";
import { PipelineShell } from "@/components/PipelineShell";

const BASE_ASSET_URL = "https://author-www-uat.apple.com/api";

type FilterOptions = {
  tenants: string[];
  environments: string[];
  projects: string[];
  sites: string[];
  geos: string[];
  locales: string[];
};

type AssetTile = {
  id: string;
  assetKey: string;
  previewUri: string;
  assetNodePath: string;
  altText: string;
};

type AssetDetail = {
  id: string;
  assetKey: string;
  assetNodePath: string;
  sectionPath: string;
  sectionKey: string;
  previewUri: string;
  altText: string;
  accessibilityText: string;
  viewports: Record<string, any>;
  assetMetadata: Record<string, any>;
};

export default function AssetFinderPage() {
  const [options, setOptions] = useState<FilterOptions>({
    tenants: [],
    environments: [],
    projects: [],
    sites: [],
    geos: [],
    locales: [],
  });

  const [filters, setFilters] = useState({
    tenant: "Apple COM CMS",
    environment: "Stage",
    project: "Rome",
    site: "ipad",
    geo: "WW",
    locale: "en_US",
  });

  const [assets, setAssets] = useState<AssetTile[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [selectedAssetId, setSelectedAssetId] = useState<string | null>(null);
  const [assetDetail, setAssetDetail] = useState<AssetDetail | null>(null);
  const [showDetail, setShowDetail] = useState(false);

  useEffect(() => {
    fetchOptions();
    handleFilter();
  }, []);

  const fetchOptions = async () => {
    try {
      const response = await fetch("/api/asset-finder/options");
      if (response.ok) {
        const data = await response.json();
        setOptions(data);
      }
    } catch (error) {
      console.error("Failed to fetch filter options", error);
    }
  };

  const handleFilter = async () => {
    setLoading(true);
    try {
      const response = await fetch("/api/asset-finder/search", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(filters),
      });
      if (response.ok) {
        const data = await response.json();
        setAssets(data.tiles);
        setTotalCount(data.totalCount);
      }
    } catch (error) {
      console.error("Failed to search assets", error);
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    setFilters({
      tenant: "Apple COM CMS",
      environment: "Stage",
      project: "Rome",
      site: "ipad",
      geo: "WW",
      locale: "en_US",
    });
  };

  const handleOpenDetail = async (id: string) => {
    setSelectedAssetId(id);
    setShowDetail(true);
    setAssetDetail(null);
    try {
      const response = await fetch(`/api/asset-finder/assets/${id}`);
      if (response.ok) {
        const data = await response.json();
        setAssetDetail(data);
      }
    } catch (error) {
      console.error("Failed to fetch asset detail", error);
    }
  };

  return (
    <PipelineShell currentStep="extraction" showTracker={false}>
      <div className="min-h-screen bg-[#F5F5F7] p-4 lg:p-8 font-sans">
        {/* Filter Bar */}
        <div className="bg-white rounded-3xl p-6 shadow-sm mb-8 border border-gray-200">
          <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-6">
            <FilterSelect
              label="Tenant"
              value={filters.tenant}
              options={options.tenants}
              onChange={(val) => setFilters({ ...filters, tenant: val })}
            />
            <FilterSelect
              label="Environment *"
              value={filters.environment}
              options={options.environments}
              onChange={(val) => setFilters({ ...filters, environment: val })}
            />
            <FilterSelect
              label="Project *"
              value={filters.project}
              options={options.projects}
              onChange={(val) => setFilters({ ...filters, project: val })}
            />
            <FilterSelect
              label="Sites/Pages *"
              value={filters.site}
              options={options.sites}
              onChange={(val) => setFilters({ ...filters, site: val })}
            />
            <FilterSelect
              label="Geo/Region *"
              value={filters.geo}
              options={options.geos}
              onChange={(val) => setFilters({ ...filters, geo: val })}
            />
            <FilterSelect
              label="Locale *"
              value={filters.locale}
              options={options.locales}
              onChange={(val) => setFilters({ ...filters, locale: val })}
            />
          </div>
          <div className="flex justify-end gap-3">
            <button
              onClick={handleFilter}
              className="bg-[#3466D1] text-white px-8 py-2.5 rounded-xl font-bold hover:bg-blue-700 transition-colors"
            >
              Filter
            </button>
            <button
              onClick={handleReset}
              className="bg-[#3466D1] text-white px-8 py-2.5 rounded-xl font-bold hover:bg-blue-700 transition-colors"
            >
              Reset
            </button>
            <button className="bg-[#3466D1] text-white px-8 py-2.5 rounded-xl font-bold hover:bg-blue-700 transition-colors">
              Share URL
            </button>
          </div>
        </div>

        <div className="flex items-center justify-between mb-6">
          <span className="text-sm font-bold text-gray-900">
            Count : {totalCount}/{totalCount}
          </span>
          <div className="flex items-center gap-2">
            <input type="checkbox" id="localeSpecific" className="rounded border-gray-300" />
            <label htmlFor="localeSpecific" className="text-sm font-bold text-gray-900">
              Show Locale Specific Assets
            </label>
          </div>
        </div>

        {/* Results Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-6">
          {loading ? (
            <div className="col-span-full flex justify-center py-20">
              <ArrowPathIcon className="size-10 text-primary animate-spin" />
            </div>
          ) : assets.length === 0 ? (
            <div className="col-span-full text-center py-20 text-gray-500 font-bold">
              No assets found. Try adjusting your filters.
            </div>
          ) : (
            assets.map((asset) => (
              <AssetTile
                key={asset.id}
                asset={asset}
                onOpenDetail={() => handleOpenDetail(asset.id)}
              />
            ))
          )}
        </div>
      </div>

      {/* Detail Modal */}
      {showDetail && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/50 backdrop-blur-sm">
          <div className="bg-white rounded-[2rem] w-full max-w-4xl max-h-[90vh] overflow-hidden flex flex-col shadow-2xl">
            <div className="p-6 border-b border-gray-100 flex items-center justify-between shrink-0">
              <h3 className="text-xl font-black text-gray-900">Asset Information</h3>
              <button
                onClick={() => setShowDetail(false)}
                className="p-2 hover:bg-gray-100 rounded-full transition-colors"
              >
                <XMarkIcon className="size-6 text-gray-500" />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-8 custom-scrollbar">
              {!assetDetail ? (
                <div className="flex justify-center py-20">
                  <ArrowPathIcon className="size-10 text-primary animate-spin" />
                </div>
              ) : (
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-12">
                  <div className="space-y-6">
                    <div className="bg-[#F5F5F7] rounded-3xl p-8 flex items-center justify-center aspect-square border border-gray-100 shadow-inner">
                      {assetDetail.previewUri ? (
                        <img
                          src={`${BASE_ASSET_URL}${assetDetail.previewUri}`}
                          alt={assetDetail.altText || "Asset Preview"}
                          className="max-w-full max-h-full object-contain drop-shadow-lg"
                          onError={(e) => (e.currentTarget.src = "/logo.png")}
                        />
                      ) : (
                        <PhotoIcon className="size-20 text-gray-300" />
                      )}
                    </div>
                  </div>
                  <div className="space-y-8">
                    <InfoSection label="Asset Identity">
                      <InfoItem label="Asset Key" value={assetDetail.assetKey} />
                      <InfoItem label="Node Path" value={assetDetail.assetNodePath} />
                      <InfoItem label="Section Key" value={assetDetail.sectionKey} />
                      <InfoItem label="Section Path" value={assetDetail.sectionPath} />
                    </InfoSection>

                    <InfoSection label="Content & Metadata">
                      <InfoItem label="Alt Text" value={assetDetail.altText} />
                      <InfoItem label="A11y Text" value={assetDetail.accessibilityText} />
                      <InfoItem label="Preview URI" value={assetDetail.previewUri} />
                    </InfoSection>

                    <InfoSection label="Viewport Information">
                        <div className="bg-gray-50 rounded-xl p-4 font-mono text-xs overflow-auto max-h-40 custom-scrollbar">
                            <pre>{JSON.stringify(assetDetail.viewports, null, 2)}</pre>
                        </div>
                    </InfoSection>
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

function FilterSelect({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: string[];
  onChange: (val: string) => void;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-sm font-bold text-gray-700 flex items-center gap-1.5 px-1">
        {label}
        <InformationCircleIcon className="size-4 text-gray-400" />
      </label>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="bg-white border border-gray-300 rounded-xl px-4 py-2.5 text-sm font-medium focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none appearance-none cursor-pointer"
        style={{ backgroundImage: 'url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' fill=\'none\' viewBox=\'0 0 20 20\'%3E%3Cpath stroke=\'%236B7280\' stroke-linecap=\'round\' stroke-linejoin=\'round\' stroke-width=\'1.5\' d=\'m6 8 4 4 4-4\'/%3E%3C/svg%3E")', backgroundPosition: 'right 0.75rem center', backgroundRepeat: 'no-repeat', backgroundSize: '1.5em 1.5em' }}
      >
        <option value={value}>{value}</option>
        {options.filter(o => o !== value).map((opt) => (
          <option key={opt} value={opt}>
            {opt}
          </option>
        ))}
      </select>
    </div>
  );
}

function AssetTile({ asset, onOpenDetail }: { asset: AssetTile; onOpenDetail: () => void }) {
  return (
    <div className="bg-white rounded-3xl p-6 shadow-sm border border-gray-100 hover:shadow-md transition-all group relative">
      <div className="bg-[#F5F5F7] rounded-2xl aspect-square flex items-center justify-center mb-4 overflow-hidden shadow-inner border border-gray-50">
        {asset.previewUri ? (
          <img
            src={`${BASE_ASSET_URL}${asset.previewUri}`}
            alt={asset.altText || "Asset Preview"}
            className="max-w-[70%] max-h-[70%] object-contain group-hover:scale-110 transition-transform duration-300"
            onError={(e) => (e.currentTarget.src = "/logo.png")}
          />
        ) : (
          <PhotoIcon className="size-12 text-gray-300" />
        )}
      </div>
      <div className="space-y-1">
        <p className="text-[10px] uppercase font-black tracking-widest text-gray-400">
          {asset.assetKey}
        </p>
        <p className="text-sm font-bold text-gray-900 truncate" title={asset.assetNodePath}>
          {asset.assetNodePath.split('/').pop()}
        </p>
      </div>
      <button
        onClick={onOpenDetail}
        className="absolute bottom-6 right-6 p-1.5 bg-white rounded-full shadow-lg border border-gray-100 opacity-0 group-hover:opacity-100 transition-opacity hover:bg-gray-50"
      >
        <InformationCircleIcon className="size-5 text-[#3466D1]" />
      </button>
    </div>
  );
}

function InfoSection({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-4">
      <h4 className="text-xs font-black text-primary uppercase tracking-[0.2em] border-b border-primary/10 pb-2">
        {label}
      </h4>
      <div className="space-y-4">{children}</div>
    </div>
  );
}

function InfoItem({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="space-y-1">
      <p className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">{label}</p>
      <p className="text-sm font-semibold text-gray-900 break-all">{value || "—"}</p>
    </div>
  );
}
