'use client';

import { useState } from "react";
import { PipelineShell } from "@/components/PipelineShell";
import {
  SearchInterface,
} from "@/components/search/SearchInterface";
import {
  SemanticSearchResults,
  type SemanticSearchResultRecord,
} from "@/components/search/SemanticSearchResults";

const isRecord = (value: unknown): value is Record<string, unknown> =>
  Boolean(value && typeof value === "object" && !Array.isArray(value));

const extractResults = (payload: unknown): SemanticSearchResultRecord[] => {
  if (Array.isArray(payload)) {
    return payload as SemanticSearchResultRecord[];
  }
  if (isRecord(payload)) {
    if (Array.isArray(payload.results)) {
      return payload.results as SemanticSearchResultRecord[];
    }
    if (isRecord(payload.body) && Array.isArray(payload.body.results)) {
      return payload.body.results as SemanticSearchResultRecord[];
    }
    // Spring Boot backend wraps in { body: [...] }
    if (Array.isArray(payload.body)) {
      return payload.body as SemanticSearchResultRecord[];
    }
  }
  return [];
};

export default function SemanticSearchPage() {
  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState<SemanticSearchResultRecord[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);

  const handleSearch = async () => {
    if (!query.trim()) return;

    setIsSearching(true);
    setSearchError(null);
    setSearchResults([]);

    try {
      const response = await fetch("/api/semantic-search", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query: query.trim() }),
      });
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload?.error ?? "Semantic search request failed.");
      }
      setSearchResults(extractResults(payload.body ?? payload));
    } catch (error) {
      setSearchResults([]);
      setSearchError(
        error instanceof Error ? error.message : "Unable to complete semantic search."
      );
    } finally {
      setIsSearching(false);
    }
  };

  return (
    <PipelineShell currentStep="enrichment" showTracker={false}>
      <main className="mx-auto max-w-6xl p-4 lg:p-8">
        <div className="mb-8">
          <h1 className="text-2xl lg:text-3xl font-bold">Semantic Search</h1>
        </div>
        <div className="card px-4 py-8 lg:px-16 lg:py-12">
          <div className="flex justify-end gap-4 text-sm font-semibold text-primary">
            <a href="/chatbot" className="hover:underline">
              Open Chatbot
            </a>
          </div>

          <div className="mt-8 flex flex-col items-center gap-8">
            <div className="text-center">
              <h1 className="text-[32px] font-medium tracking-[-0.768px] text-[#111215] md:text-[48px]">
                What are you looking for?
              </h1>
              <p className="mt-2 text-sm text-[#4d4d4d]">
                Ask anything — our engine understands your intent using AI and enriched content.
              </p>
            </div>

            <SearchInterface
              searchQuery={query}
              setSearchQuery={setQuery}
              handleSearch={handleSearch}
              filters={[]}
              toggleFilter={() => {}}
            />

            {isSearching && (
              <p className="text-sm text-slate-500">Searching…</p>
            )}

            {searchError && (
              <p className="text-sm text-rose-600" role="alert">
                {searchError}
              </p>
            )}

            <SemanticSearchResults results={searchResults} isLoading={isSearching} />

            {!isSearching && searchResults.length === 0 && !searchError && query && (
              <p className="text-center text-sm text-slate-500">
                No results found. Try a different query.
              </p>
            )}
          </div>
        </div>
      </main>
    </PipelineShell>
  );
}
