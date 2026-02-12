'use client';

import { useEffect, useMemo, useState } from "react";
import { PipelineShell } from "@/components/PipelineShell";
import {
  SearchInterface,
  type SearchFilter,
} from "@/components/search/SearchInterface";
import {
  SearchResults,
  type SearchResultRecord,
} from "@/components/search/SearchResults";

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return Boolean(value && typeof value === "object" && !Array.isArray(value));
};

type RefinementChip = SearchFilter & {
  value: string;
  type?: string;
};

type RawChip = {
  type?: string;
  value?: string;
  count?: number;
};

const normalizeRefinementPayload = (payload: unknown): RefinementChip[] => {
  if (!Array.isArray(payload)) return [];
  const displayLabel = (type?: string) => {
    if (!type) return undefined;
    if (type === "sectionName") return "Section Name";
    if (type === "sectionKey") return "Section Key";
    return type;
  };
  return payload
    .filter((chip): chip is RawChip => isRecord(chip) && typeof chip.value === "string")
    .map((chip, index) => {
      const typeLabel = displayLabel(chip.type);
      const label = typeLabel ? `${typeLabel}: ${chip.value}` : chip.value ?? `Chip ${index + 1}`;
      return {
        id: `${chip.type ?? "chip"}-${chip.value}-${index}`,
        label,
        count: chip.count,
        isActive: false,
        value: chip.value ?? "",
        type: chip.type,
        __index: index,
      } as RefinementChip & { __index: number };
    })
    .sort((a, b) => {
      const aPinned = a.type === "sectionName" ? 1 : 0;
      const bPinned = b.type === "sectionName" ? 1 : 0;
      if (aPinned !== bPinned) return bPinned - aPinned;
      return a.__index - b.__index;
    })
    .map(({ __index, ...chip }) => chip);
};

const appendContextValue = (
  target: Record<string, unknown>,
  pathParts: string[],
  value: string,
) => {
  if (!pathParts.length) return;
  let currentLevel = target;
  for (let i = 0; i < pathParts.length - 1; i += 1) {
    const part = pathParts[i];
    if (!isRecord(currentLevel[part])) {
      currentLevel[part] = {};
    }
    currentLevel = currentLevel[part] as Record<string, unknown>;
  }
  const leaf = pathParts[pathParts.length - 1];
  const existing = currentLevel[leaf];
  if (!Array.isArray(existing)) {
    currentLevel[leaf] = [];
  }
  const bucket = currentLevel[leaf] as string[];
  if (!bucket.includes(value)) {
    bucket.push(value);
  }
};

const buildSearchRequest = (query: string, chips: RefinementChip[]) => {
  const request: {
    query: string;
    tags: string[];
    keywords: string[];
    context: Record<string, unknown>;
    original_field_name?: string;
  } = {
    query,
    tags: [],
    keywords: [],
    context: {},
  };

  chips.forEach((chip) => {
    if (!chip.type || !chip.value) return;
    if (chip.type === "Tag") {
      request.tags.push(chip.value);
    } else if (chip.type === "Keyword") {
      request.keywords.push(chip.value);
    } else if (chip.type === "sectionName") {
      if (!request.original_field_name) {
        request.original_field_name = chip.value;
      } else if (request.original_field_name !== chip.value) {
        appendContextValue(request.context, ["facets", "sectionName"], chip.value);
      }
    } else if (chip.type === "sectionKey") {
      appendContextValue(request.context, ["facets", "sectionKey"], chip.value);
    } else if (chip.type.startsWith("Context:")) {
      const fullPath = chip.type.substring("Context:".length);
      const pathParts = fullPath.split(".").map((part) => part.trim()).filter(Boolean);
      if (!pathParts.length) return;
      appendContextValue(request.context, pathParts, chip.value);
    }
  });

  return request;
};

const extractResults = (payload: unknown): SearchResultRecord[] => {
  if (Array.isArray(payload)) {
    return payload as SearchResultRecord[];
  }
  if (isRecord(payload)) {
    if (Array.isArray(payload.results)) {
      return payload.results as SearchResultRecord[];
    }
    if (isRecord(payload.body) && Array.isArray(payload.body.results)) {
      return payload.body.results as SearchResultRecord[];
    }
  }
  return [];
};

export default function SearchPage() {
  const [query, setQuery] = useState("");
  const [refinementChips, setRefinementChips] = useState<RefinementChip[]>([]);
  const activeChips = useMemo(() => refinementChips.filter((chip) => chip.isActive), [refinementChips]);
  const [searchResults, setSearchResults] = useState<SearchResultRecord[]>([]);
  const [isLoadingSuggestions, setIsLoadingSuggestions] = useState(false);
  const [isSearching, setIsSearching] = useState(false);
  const [refineError, setRefineError] = useState<string | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);

  const handleInitialSearch = async () => {
    if (!query.trim()) return;
    setIsLoadingSuggestions(true);
    setRefineError(null);
    setRefinementChips([]);
    setSearchResults([]);
    try {
      const response = await fetch(`/api/refine?query=${encodeURIComponent(query.trim())}`);
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload?.error ?? "Unable to retrieve refinement chips.");
      }
      const normalized = normalizeRefinementPayload(payload.body ?? payload);
      setRefinementChips(normalized);
    } catch (error) {
      setRefineError(error instanceof Error ? error.message : "Unable to fetch refinement chips.");
    } finally {
      setIsLoadingSuggestions(false);
    }
  };

  useEffect(() => {
    if (!activeChips.length || !query.trim()) {
      setSearchResults([]);
      return;
    }
    let cancelled = false;
    const performSearch = async () => {
      setIsSearching(true);
      setSearchError(null);
      try {
        const response = await fetch("/api/search", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(buildSearchRequest(query.trim(), activeChips)),
        });
        const payload = await response.json();
        if (!response.ok) {
          throw new Error(payload?.error ?? "Search request failed.");
        }
        if (!cancelled) {
          setSearchResults(extractResults(payload.body ?? payload));
        }
      } catch (error) {
        if (!cancelled) {
          setSearchResults([]);
          setSearchError(error instanceof Error ? error.message : "Unable to complete search.");
        }
      } finally {
        if (!cancelled) {
          setIsSearching(false);
        }
      }
    };

    performSearch();
    return () => {
      cancelled = true;
    };
  }, [activeChips, query]);

  const toggleFilter = (chip: RefinementChip) => {
    setRefinementChips((previous) =>
      previous.map((entry) => (entry.id === chip.id ? { ...entry, isActive: !entry.isActive } : entry)),
    );
  };

  return (
    <PipelineShell currentStep="ingestion" showTracker={false}>
      <main className="mx-auto max-w-6xl p-4 lg:p-8">
        <div className="mb-8"><h1 className="text-2xl lg:text-3xl font-bold">Search</h1></div>
        <div className="card px-4 py-8 lg:px-16 lg:py-12">
          <div className="flex justify-end gap-4 text-sm font-semibold text-primary">
            <a href="/ingestion" className="hover:underline">
              Upload JSON
            </a>
            <a href="/chatbot" className="hover:underline">
              Open Chatbot
            </a>
          </div>

          <div className="mt-8 flex flex-col items-center gap-8">
            <div className="text-center">
              <h1 className="text-[32px] font-medium tracking-[-0.768px] text-[#111215] md:text-[48px]">
                What are you looking for?
              </h1>
              <p className="mt-2 text-sm text-[#4d4d4d]">Run an initial query to generate intelligent refinements.</p>
            </div>

            <SearchInterface
              searchQuery={query}
              setSearchQuery={setQuery}
              handleSearch={handleInitialSearch}
              filters={refinementChips}
              toggleFilter={toggleFilter}
            />

            {isLoadingSuggestions && <p className="text-sm text-slate-500">Loading suggestions…</p>}
            {refineError && (
              <p className="text-sm text-rose-600" role="alert">
                {refineError}
              </p>
            )}

            <SearchResults results={searchResults} isLoading={isSearching} />

            {!activeChips.length && !isLoadingSuggestions && (
              <p className="text-center text-sm text-slate-500">
                Select one or more refinement chips to trigger a contextual search.
              </p>
            )}

            {searchError && (
              <p className="text-sm text-rose-600" role="alert">
                {searchError}
              </p>
            )}
          </div>
        </div>
      </main>
    </PipelineShell>
  );
}