import { Suspense } from "react";
import EnrichmentPageClient from "./EnrichmentPageClient";

export default function EnrichmentPage() {
  return (
    <Suspense
      fallback={
        <div className="flex min-h-screen items-center justify-center bg-slate-50 px-6 py-20">
          <div className="max-w-xl rounded-3xl border border-slate-200 bg-white p-10 text-center shadow-sm">
            <p className="text-xs uppercase tracking-wide text-slate-400">Enrichment</p>
            <h1 className="mt-2 text-2xl font-semibold text-slate-900">Loading enrichment dashboard…</h1>
            <p className="mt-4 text-sm text-slate-500">Fetching the latest enrichment context.</p>
          </div>
        </div>
      }
    >
      <EnrichmentPageClient />
    </Suspense>
  );
}