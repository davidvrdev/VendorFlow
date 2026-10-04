import "server-only";
import { serverApiFetch } from "@/lib/api/server";
import { chasingSettingsSchema, type ChasingSettings } from "./schemas";

export async function fetchChasingSettings(): Promise<ChasingSettings> {
  return chasingSettingsSchema.parse(await serverApiFetch<unknown>("/organization/chasing"));
}

/** Null on failure: the vendor page must still render; the card then just omits the "turned off" hint. */
export async function fetchChasingSettingsOrNull(): Promise<ChasingSettings | null> {
  try {
    return await fetchChasingSettings();
  } catch {
    return null;
  }
}
