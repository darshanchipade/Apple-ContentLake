"use client";

import { useMemo } from "react";

export type ContentRoleRecord = {
  role: string;
  text: string;
  href?: string;
};

export type MediaItemRecord = {
  type: string;
  label: string;
  url: string;
};

export type SemanticSearchResultRecord = {
  rank: number;
  sectionPath: string;
  sourceUrl?: string;
  finalScore: number;
  content: ContentRoleRecord[];
  media: MediaItemRecord[];
  /** Best-matching chunk text (snippet) — shown first when available */
  snippet?: string;
  /** Field name of the fragment that matched the query */
  matchedFieldName?: string;
};

type SemanticSearchResultsProps = {
  results: SemanticSearchResultRecord[] | { results: SemanticSearchResultRecord[] } | undefined;
  isLoading: boolean;
};

export function SemanticSearchResults({ results, isLoading }: SemanticSearchResultsProps) {
  const list = useMemo(() => {
    if (Array.isArray(results)) return results;
    if (results && Array.isArray((results as { results: SemanticSearchResultRecord[] }).results))
      return (results as { results: SemanticSearchResultRecord[] }).results;
    return [];
  }, [results]);

  if (isLoading) {
    return (
      <div className="mt-6 flex flex-col items-center gap-3">
        <div className="flex gap-1.5">
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              className="h-2 w-2 rounded-full bg-[#0066cc] animate-bounce"
              style={{ animationDelay: `${i * 0.15}s` }}
            />
          ))}
        </div>
        <p className="text-sm text-[#86868b]">Finding the best matches…</p>
      </div>
    );
  }

  if (!list.length) {
    return null;
  }

  return (
    <div className="flex w-full flex-col items-center gap-6 mt-4">
      <div className="flex w-full max-w-3xl items-center justify-between">
        <div className="text-[21px] font-semibold text-[#1d1d1f]">
          Top Section Packs ({list.length})
        </div>
      </div>

      <div className="flex w-full max-w-3xl flex-col gap-6">
        {list.map((result, index) => {
          // Strip ingestion scheme prefixes (e.g. 'html-extraction:') from display
          const rawSection = result.sectionPath || result.sourceUrl || "Unknown Section";
          const section = rawSection.replace(/^[a-z][a-z0-9+\-.]*:(?=https?:\/\/)/i, "");
          const contentItems = Array.isArray(result.content) ? result.content : [];
          const mediaItems = Array.isArray(result.media) ? result.media : [];
          const resultId = String(result.rank || index + 1);
          // Cap at 100% — vector similarity scores can exceed 1.0
          const scorePct = result.finalScore ? Math.min(Math.round(result.finalScore * 100), 100) : null;
          const scoreDisplay = scorePct !== null ? `${scorePct}% Match` : "";

          const cardContent = (
            <article
              className="box-border flex flex-col gap-4 rounded-[16px] bg-[#ffffff] p-6 shadow-[0px_2px_10px_rgba(0,0,0,0.08)] ring-1 ring-[#e5e5ea]"
            >
              {/* Header: Section + Score */}
              <div className="flex items-center justify-between">
                <div className="flex flex-col">
                  <p className="text-[10px] font-semibold uppercase tracking-widest text-[#86868b]">Section Path</p>
                  <p className="mt-0.5 text-[13px] font-medium text-[#86868b] break-all">
                    {section}
                  </p>
                </div>
                {scoreDisplay && (
                  <div className="flex flex-col items-end">
                    <p className="text-[10px] font-semibold uppercase tracking-widest text-[#86868b]">Relevance</p>
                    <p className="mt-0.5 text-[14px] font-semibold text-[#1d1d1f]">{scoreDisplay}</p>
                  </div>
                )}
              </div>

              {/* Content Sections */}
              {contentItems.length > 0 && (
                 <div className="flex flex-col gap-3">
                   {contentItems.map((c, i) => (
                     <div key={i} className="flex flex-col">
                       <span className="text-[11px] font-medium uppercase tracking-wide text-[#86868b]">{c.role}</span>
                       {c.href ? (
                          <a
                            href={c.href}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 text-[14px] font-medium text-[#0066cc] hover:underline leading-[22px]"
                          >
                            {c.text}
                            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor" className="h-3 w-3 flex-shrink-0">
                              <path d="M6.22 8.72a.75.75 0 0 0 1.06 1.06l5.22-5.22v1.69a.75.75 0 0 0 1.5 0v-3.5a.75.75 0 0 0-.75-.75h-3.5a.75.75 0 0 0 0 1.5h1.69L6.22 8.72Z" />
                              <path d="M3.5 6.75c0-.69.56-1.25 1.25-1.25H7A.75.75 0 0 0 7 4H4.75A2.75 2.75 0 0 0 2 6.75v4.5A2.75 2.75 0 0 0 4.75 14h4.5A2.75 2.75 0 0 0 12 11.25V9a.75.75 0 0 0-1.5 0v2.25c0 .69-.56 1.25-1.25 1.25h-4.5c-.69 0-1.25-.56-1.25-1.25v-4.5Z" />
                            </svg>
                          </a>
                        ) : (
                          <span className="text-[15px] leading-[24px] text-[#1d1d1f]">{c.text}</span>
                        )}
                     </div>
                   ))}
                 </div>
              )}

              {/* Media Preview Gallery */}
              {mediaItems.length > 0 && (
                <>
                  <div className="h-px w-full bg-[#f5f5f7] mt-1" />
                  <div className="mt-2 flex flex-wrap gap-4">
                    {mediaItems.map((m, i) => (
                      <div key={i} className="flex flex-col items-center gap-2">
                        {m.url && (
                          <div className="relative h-[100px] w-[140px] flex-shrink-0 overflow-hidden rounded-[8px] border border-[#e5e5ea] bg-[#f5f5f7] flex items-center justify-center p-2">
                            {/* eslint-disable-next-line @next/next/no-img-element */}
                            <img
                              src={m.url}
                              alt={m.label || `${m.type} ${i + 1}`}
                              className="max-h-full max-w-full object-contain mix-blend-multiply"
                              onError={(e) => {
                                (e.currentTarget as HTMLImageElement).style.display = "none";
                              }}
                            />
                          </div>
                        )}
                        {m.label && (
                          <span className="text-[11px] font-medium text-[#86868b] text-center w-[140px] truncate" title={m.label}>
                            {m.label}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                </>
              )}
            </article>
          );

          // Cards are never wrapped in a link — sourceUrl is retained in the data only
          return (
            <div key={resultId}>
              {cardContent}
            </div>
          );
        })}
      </div>
    </div>
  );
}

