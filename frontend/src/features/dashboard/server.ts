import "server-only";
import type { Page } from "@/features/organization/types";
import { serverApiFetch } from "@/lib/api/server";
import type { AttentionItem, DashboardSummary } from "./types";

export const ATTENTION_PAGE_SIZE = 10;

export const fetchDashboardSummary = () => serverApiFetch<DashboardSummary>("/dashboard/summary");

/** `page` is 1-based here (the API is 0-based). */
export const fetchAttention = (page: number) =>
  serverApiFetch<Page<AttentionItem>>(`/dashboard/attention?page=${page - 1}&size=${ATTENTION_PAGE_SIZE}`);
