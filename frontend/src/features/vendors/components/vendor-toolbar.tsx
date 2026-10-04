"use client";

import { Search } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { VENDOR_COMPLIANCES, type VendorCompliance } from "@/features/compliance/types";
import {
  STATUS_FILTERS,
  toSearchString,
  withFilterChange,
  type StatusFilter,
  type VendorListState,
} from "../list-state";
import { VendorToolbarActions } from "./vendor-toolbar-actions";

const SEARCH_DEBOUNCE_MS = 300;
const ALL_CATEGORIES = "__all__";
const ALL_COMPLIANCE = "__all__";
const COMPLIANCE_LABELS: Record<VendorCompliance, string> = {
  NON_COMPLIANT: "Non-compliant",
  ATTENTION: "Needs attention",
  COMPLIANT: "Compliant",
};
const STATUS_LABELS: Record<StatusFilter, string> = { ACTIVE: "Active", INACTIVE: "Inactive", ALL: "All" };

interface VendorToolbarProps {
  state: VendorListState;
  categories: string[];
  canCreate: boolean;
  canImport: boolean;
  /** Size of the filtered result, to pre-check the export cap. */
  totalItems: number;
}

/** Search + filters. They only edit the URL (router.replace); the Server Component re-reads the list. */
export function VendorToolbar({ state, categories, canCreate, canImport, totalItems }: VendorToolbarProps) {
  const router = useRouter();
  const [search, setSearch] = useState(state.q);
  const [seenQ, setSeenQ] = useState(state.q);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // The URL can change under us (e.g. "Clear filters"): follow it unless it is just our own debounced echo.
  if (state.q !== seenQ) {
    setSeenQ(state.q);
    if (state.q !== search.trim()) setSearch(state.q);
  }

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  function navigate(next: VendorListState) {
    if (timer.current) clearTimeout(timer.current); // a pending search is folded into `next` by the callers
    router.replace(`/vendors${toSearchString(next)}`, { scroll: false });
  }

  function onSearchChange(value: string) {
    setSearch(value);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      const q = value.trim();
      if (q !== state.q) navigate(withFilterChange(state, { q }));
    }, SEARCH_DEBOUNCE_MS);
  }

  // Keep the active category selectable even if it no longer appears in the org's category list.
  const categoryOptions = state.category && !categories.includes(state.category) ? [state.category, ...categories] : categories;

  return (
    <div className="mb-4 flex flex-wrap items-end gap-3">
      <div className="grid min-w-56 flex-1 gap-1.5 sm:max-w-sm">
        <Label htmlFor="vendor-search">Search vendors</Label>
        <div className="relative">
          <Search className="pointer-events-none absolute top-2 left-2.5 size-4 text-muted-foreground" aria-hidden="true" />
          <Input
            id="vendor-search"
            type="search"
            className="pl-8"
            placeholder="Company, contact or email"
            value={search}
            maxLength={200}
            onChange={(event) => onSearchChange(event.target.value)}
          />
        </div>
      </div>

      <div className="grid gap-1.5">
        <Label htmlFor="vendor-status">Status</Label>
        <Select value={state.status} onValueChange={(value) => navigate(withFilterChange(state, { q: search.trim(), status: value as StatusFilter }))}>
          <SelectTrigger id="vendor-status" className="w-36">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_FILTERS.map((status) => (
              <SelectItem key={status} value={status}>
                {STATUS_LABELS[status]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="grid gap-1.5">
        <Label htmlFor="vendor-compliance">Compliance</Label>
        <Select
          value={state.compliance || ALL_COMPLIANCE}
          onValueChange={(value) =>
            navigate(withFilterChange(state, { q: search.trim(), compliance: value === ALL_COMPLIANCE ? "" : (value as VendorCompliance) }))
          }
        >
          <SelectTrigger id="vendor-compliance" className="w-44">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL_COMPLIANCE}>All</SelectItem>
            {VENDOR_COMPLIANCES.map((value) => (
              <SelectItem key={value} value={value}>
                {COMPLIANCE_LABELS[value]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="grid gap-1.5">
        <Label htmlFor="vendor-category">Category</Label>
        <Select
          value={state.category || ALL_CATEGORIES}
          onValueChange={(value) => navigate(withFilterChange(state, { q: search.trim(), category: value === ALL_CATEGORIES ? "" : value }))}
        >
          <SelectTrigger id="vendor-category" className="w-44">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL_CATEGORIES}>All categories</SelectItem>
            {categoryOptions.map((category) => (
              <SelectItem key={category} value={category}>
                {category}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <VendorToolbarActions state={state} totalItems={totalItems} canCreate={canCreate} canImport={canImport} />
    </div>
  );
}
