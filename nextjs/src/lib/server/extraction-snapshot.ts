import { promises as fs } from "fs";
import path from "path";
import { ExtractionSnapshot } from "../extraction-snapshot";

const CACHE_DIR =
  process.env.EXTRACTION_CACHE_DIR ?? path.join(process.cwd(), ".extraction-cache");

const ensureCacheDir = async () => {
  await fs.mkdir(CACHE_DIR, { recursive: true });
};

export const writeSnapshot = async (id: string, payload: ExtractionSnapshot) => {
  if (!id) throw new Error("Snapshot id is required");
  await ensureCacheDir();
  const target = path.join(CACHE_DIR, `${id}.json`);
  await fs.writeFile(target, JSON.stringify(payload), "utf8");
};

export const readSnapshot = async (id: string): Promise<ExtractionSnapshot | null> => {
  try {
    const file = path.join(CACHE_DIR, `${id}.json`);
    const contents = await fs.readFile(file, "utf8");
    return JSON.parse(contents) as ExtractionSnapshot;
  } catch (error: unknown) {
    if ((error as NodeJS.ErrnoException)?.code === "ENOENT") {
      return null;
    }
    throw error;
  }
};

export const deleteSnapshot = async (id: string) => {
  try {
    const file = path.join(CACHE_DIR, `${id}.json`);
    await fs.unlink(file);
  } catch (error: unknown) {
    if ((error as NodeJS.ErrnoException)?.code !== "ENOENT") {
      throw error;
    }
  }
};
