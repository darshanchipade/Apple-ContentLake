"use client";

import type { Dispatch, SetStateAction } from "react";
import clsx from "clsx";
import { CloseIcon, SearchIcon } from "@/components/search/Icons";

export type SearchFilter = {
  id: string;
  label: string;
  count?: number;
  isActive: boolean;
};

type SearchInterfaceProps<TFilter extends SearchFilter = SearchFilter> = {
  searchQuery: string;
  setSearchQuery: Dispatch<SetStateAction<string>>;
  handleSearch: () => void;
  filters: TFilter[];
  toggleFilter: (filter: TFilter) => void;
};

export function SearchInterface<TFilter extends SearchFilter>({
  searchQuery,
  setSearchQuery,
  handleSearch,
  filters,
  toggleFilter,
}: SearchInterfaceProps<TFilter>) {
  const clearSearch = () => setSearchQuery("");

  const onFormSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    handleSearch();
  };

  return (
    <div className="flex w-full flex-col items-center gap-4">
      <div className="w-full">
        <form onSubmit={onFormSubmit} className="flex w-full max-w-3xl flex-col gap-3 md:flex-row md:items-center">
          <div className="relative h-[50px] w-full rounded-[12px] bg-white">
            <div className="box-border flex h-full items-center justify-between overflow-clip px-4 py-3">
              <div className="flex w-full items-center gap-2 font-medium text-[#4d4d4d]">
                <SearchIcon className="size-4 text-[#4d4d4d]" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(event) => setSearchQuery(event.target.value)}
                  placeholder="Search..."
                  className="w-full border-none bg-transparent text-[14px] leading-[20px] text-[#4d4d4d] placeholder-[#9aa0a6] outline-none"
                />
              </div>
              {searchQuery && (
                <button
                  type="button"
                  onClick={clearSearch}
                  className="text-gray-500 transition-colors hover:text-gray-600"
                  aria-label="Clear search"
                >
                  <CloseIcon className="size-4" />
                </button>
              )}
            </div>
            <div className="pointer-events-none absolute inset-0 rounded-[12px] border border-[#d0d1d4] shadow-[0px_0.5px_2.5px_0px_rgba(0,0,0,0.3)]" />
          </div>
          <button
            type="submit"
            className="h-[50px] rounded-[12px] bg-[#2180f9] px-6 font-semibold text-white shadow transition-colors hover:bg-blue-600"
          >
            Search
          </button>
        </form>
      </div>

      {filters.length > 0 && (
        <div className="flex w-full max-w-4xl flex-col items-center gap-4">
          <div className="text-[14px] text-[#111215]">Refine your search by</div>
          <div className="flex w-full flex-wrap items-start gap-2.5">
            {filters.map((filter) => (
              <button
                key={filter.id}
                type="button"
                onClick={() => toggleFilter(filter)}
                className={clsx(
                  "box-border flex items-center justify-center gap-1 rounded-[24px] px-3 py-2 text-[12px] font-medium tracking-[-0.432px] shadow-[0px_1px_2.5px_0px_rgba(0,0,0,0.24),0px_0px_0px_0.5px_rgba(0,0,0,0.12)] transition-all duration-200",
                  filter.isActive ? "bg-[#2180f9] text-white" : "bg-white text-[#4d4d4d] hover:bg-gray-50",
                )}
              >
                {filter.label}
                {filter.count != null && <span>({filter.count})</span>}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}