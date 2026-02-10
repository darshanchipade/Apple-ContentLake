"use client";

import type { ExtractionSnapshot } from "@/lib/extraction-snapshot";

const DB_NAME = "ExtractionSnapshots";
const STORE_NAME = "snapshots";

type SnapshotStoreResult =
  | { ok: true }
  | {
      ok: false;
      message?: string;
    };

const openDatabase = (): Promise<IDBDatabase> => {
  return new Promise((resolve, reject) => {
    if (typeof window === "undefined" || !window.indexedDB) {
      reject(new Error("IndexedDB is not available in this environment."));
      return;
    }

    const request = window.indexedDB.open(DB_NAME, 1);

    request.onerror = () => {
      reject(request.error ?? new Error("Failed to open IndexedDB."));
    };

    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME);
      }
    };

    request.onsuccess = () => {
      resolve(request.result);
    };
  });
};

export const storeClientSnapshot = async (
  id: string,
  payload: ExtractionSnapshot,
): Promise<SnapshotStoreResult> => {
  try {
    const db = await openDatabase();
    await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, "readwrite");
      const store = transaction.objectStore(STORE_NAME);
      const request = store.put(payload, id);

      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error ?? new Error("Failed to store snapshot."));
    });
    return { ok: true };
  } catch (error) {
    return {
      ok: false,
      message:
        error instanceof Error ? error.message : "Failed to store snapshot in IndexedDB.",
    };
  }
};

export const readClientSnapshot = async (
  id: string,
): Promise<ExtractionSnapshot | null> => {
  try {
    const db = await openDatabase();
    return await new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, "readonly");
      const store = transaction.objectStore(STORE_NAME);
      const request = store.get(id);
      request.onsuccess = () => resolve((request.result as ExtractionSnapshot) ?? null);
      request.onerror = () => reject(request.error ?? new Error("Failed to read snapshot."));
    });
  } catch {
    return null;
  }
};
