import type { z } from "zod";
import { apiFetch } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { toApiPath } from "@/lib/api/path";
import { PORTAL_TOKEN_HEADER } from "@/features/portal/api";
import {
  chasePageSchema,
  chasingSettingsSchema,
  optOutInfoSchema,
  vendorChasingStateSchema,
  type ChasePage,
  type ChasingSettings,
  type OptOutInfo,
  type VendorChasingState,
} from "./schemas";

const enc = encodeURIComponent;

function parse<S extends z.ZodType>(schema: S, data: unknown): z.output<S> {
  const result = schema.safeParse(data);
  // Not an ApiError on purpose: callers show a generic message, never raw parser output.
  if (!result.success) throw new Error("Unexpected response from the server.");
  return result.data;
}

// ---- Staff (session + CSRF via apiFetch) ----

export async function saveChasingSettings(settings: ChasingSettings): Promise<ChasingSettings> {
  return parse(chasingSettingsSchema, await apiFetch<unknown>("/organization/chasing", { method: "PUT", json: settings }));
}

export async function getVendorChasing(vendorId: string): Promise<VendorChasingState> {
  return parse(vendorChasingStateSchema, await apiFetch<unknown>(`/vendors/${enc(vendorId)}/chasing`));
}

export async function setVendorChasingPaused(vendorId: string, paused: boolean): Promise<VendorChasingState> {
  return parse(vendorChasingStateSchema, await apiFetch<unknown>(`/vendors/${enc(vendorId)}/chasing`, { method: "PUT", json: { paused } }));
}

export async function listChases(vendorId: string, page: number, size = 10): Promise<ChasePage> {
  return parse(chasePageSchema, await apiFetch<unknown>(`/vendors/${enc(vendorId)}/chases?page=${page}&size=${size}`));
}

// ---- Public opt-out (no session): token in a header, never in a URL; no cookies, no Referer ----

async function optOutFetch(method: "GET" | "POST", token: string): Promise<unknown> {
  const response = await fetch(toApiPath("/portal/chasing/opt-out"), {
    method,
    credentials: "omit",
    cache: "no-store",
    referrerPolicy: "no-referrer",
    headers: { Accept: "application/json", [PORTAL_TOKEN_HEADER]: token },
  });
  if (!response.ok) throw await ApiError.fromResponse(response);
  return response.json();
}

/** Never changes state (safe for scanners); only shows who is asking to unsubscribe. */
export async function fetchOptOutInfo(token: string): Promise<OptOutInfo> {
  return parse(optOutInfoSchema, await optOutFetch("GET", token));
}

export async function confirmOptOut(token: string): Promise<OptOutInfo> {
  return parse(optOutInfoSchema, await optOutFetch("POST", token));
}
