import { apiFetch } from "@/lib/api/client";
import type { VendorDetail, VendorInput } from "./types";

// Browser-side writes (CSRF header added by apiFetch). Reads happen in Server Components (server.ts).
const vendorPath = (id: string) => `/vendors/${encodeURIComponent(id)}`;

export const createVendor = (input: VendorInput) => apiFetch<VendorDetail>("/vendors", { method: "POST", json: input });

export const updateVendor = (id: string, input: VendorInput) => apiFetch<VendorDetail>(vendorPath(id), { method: "PUT", json: input });

export const deactivateVendor = (id: string) => apiFetch<VendorDetail>(`${vendorPath(id)}/deactivate`, { method: "POST" });

export const reactivateVendor = (id: string) => apiFetch<VendorDetail>(`${vendorPath(id)}/reactivate`, { method: "POST" });

export const setVendorRequirements = (id: string, documentTypeIds: string[]) =>
  apiFetch<VendorDetail>(`${vendorPath(id)}/requirements`, { method: "PUT", json: { documentTypeIds } });
