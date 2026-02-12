import { Suspense } from "react";
import CleansingPageClient from "./CleansingPageClient";

export default function CleansingPage() {
  return (
    <Suspense
      fallback={
        <div className="flex min-h-screen items-center justify-center bg-white px-6 py-20">
          <div className="max-w-xl rounded-3xl border border-gray-200 bg-white p-10 text-center shadow-sm">
            <p className="text-xs uppercase tracking-wide text-gray-400">Cleansing</p>
            <h1 className="mt-2 text-2xl font-semibold text-gray-900">Loading cleansing run…</h1>
            <p className="mt-4 text-sm text-gray-500">Fetching the latest cleansing context.</p>
          </div>
        </div>
      }
    >
      <CleansingPageClient />
    </Suspense>
  );
}
