"use client";

import { useMemo, useState } from "react";
import clsx from "clsx";
import { CopyIcon, FilterIcon } from "@/components/search/Icons";

export type SearchResultRecord = {
  id?: string;
  source?: string;
  section?: string;
  content?: string;
  score?: number;
  [key: string]: unknown;
};

type SearchResultsProps = {
  results: SearchResultRecord[] | { results: SearchResultRecord[] } | undefined;
  isLoading: boolean;
  onFilter?: () => void;
};

export function SearchResults({ results, isLoading, onFilter }: SearchResultsProps) {
  const [activeTab, setActiveTab] = useState<"raw" | "format">("raw");
  const [copiedStates, setCopiedStates] = useState<Record<string, boolean>>({});

  const list = useMemo(() => {
    if (Array.isArray(results)) return results;
    if (results && Array.isArray(results.results)) return results.results;
    return [];
  }, [results]);

  const copyToClipboard = (text: string, resultId: string) => {
    if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text || "");
    }
    setCopiedStates((previous) => ({ ...previous, [resultId]: true }));
    setTimeout(() => {
      setCopiedStates((previous) => ({ ...previous, [resultId]: false }));
    }, 2000);
  };

  if (isLoading) {
    return (
      <div className="mt-6 text-sm text-slate-500">
        Searching…
      </div>
    );
  }

  if (!list.length) {
    return null;
  }

  return (
    <div className="flex w-full flex-col items-center gap-4">
      <div className="flex w-full max-w-3xl items-center justify-between">
        <div className="text-[18px] text-[#111215]">Search Results ({list.length})</div>
        <button
          type="button"
          className="flex items-center gap-1 text-[12px] font-medium text-[#111215] transition-opacity hover:opacity-70"
          onClick={onFilter}
        >
          <span>Filter</span>
          <FilterIcon className="size-4 text-[#111215]" />
        </button>
      </div>

      <div className="flex w-full max-w-3xl flex-col gap-4">
        {list.map((result, index) => {
          const source =
            (result.source as string) ??
            (result.sourceFieldName as string) ??
            (result.sourceType as string) ??
            "";
          const section = (result.section as string) ?? (result.sectionPath as string) ?? "";
          const content =
            (result.content as string) ??
            (result.cleansedText as string) ??
            (result.snippet as string) ??
            "";
          const resultId = result.id ?? String(index);

          return (
            <article
              key={resultId}
              className="box-border flex flex-col gap-4 rounded-[12px] bg-white p-4 shadow-[0px_0.5px_2.5px_0px_rgba(0,0,0,0.3)]"
            >
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-[10px] font-semibold text-[#737780]">Source</p>
                  <p className="text-[12px] font-medium text-[#111215] break-all">{source}</p>
                </div>
                <div className="w-[318px] max-w-full">
                  <p className="text-[10px] font-semibold text-[#737780]">Section</p>
                  <p className="text-[12px] font-medium text-[#111215] break-all">{section}</p>
                </div>
              </div>

              <div className="h-px w-full bg-[#d0d1d4]" />

              <div className="flex h-[22px] w-[164px] items-center rounded-[6px] bg-[rgba(0,0,0,0.01)]">
                <button
                  type="button"
                  onClick={() => setActiveTab("raw")}
                  className={clsx(
                    "mr-[-1px] h-full flex-1 rounded-[5px] text-center text-[13px] leading-[16px] transition-all",
                    activeTab === "raw" ? "bg-[#111215] text-white" : "bg-transparent text-[#737780] hover:bg-gray-100",
                  )}
                >
                  Raw
                </button>
                <button
                  type="button"
                  onClick={() => setActiveTab("format")}
                  className={clsx(
                    "mr-[-1px] h-full flex-1 rounded-[5px] text-center text-[13px] leading-[16px] transition-all",
                    activeTab === "format"
                      ? "bg-[#111215] text-white"
                      : "bg-transparent text-[#737780] hover:bg-gray-100",
                  )}
                >
                  Format
                </button>
              </div>

              <div className="text-[12px] leading-[20px] text-[#4d4d4d]">{content}</div>

              <div>
                <button
                  type="button"
                  onClick={() => copyToClipboard(content, resultId)}
                  className={clsx(
                    "rounded-[24px] transition-all duration-200",
                    copiedStates[resultId] ? "bg-gray-50" : "bg-white hover:bg-gray-50",
                  )}
                >
                  <div className="box-border flex items-center gap-1 px-3 py-[3px]">
                    <CopyIcon className="size-3.5 text-[#111215]" />
                    <span className="text-[12px] font-medium text-[#111215]">
                      {copiedStates[resultId] ? "Copied" : "Copy"}
                    </span>
                  </div>
                  <div className="pointer-events-none inset-[-1px] rounded-[25px] border border-black shadow-[0px_1px_2.5px_0px_rgba(0,122,255,0.24),0px_0px_0px_0.5px_rgba(0,122,255,0.12)]" />
                </button>
              </div>
            </article>
          );
        })}
      </div>
    </div>
  );
}