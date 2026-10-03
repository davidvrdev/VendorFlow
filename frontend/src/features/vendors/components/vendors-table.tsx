import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react";
import Link from "next/link";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDate } from "@/lib/format";
import {
  ariaSortFor,
  nextSort,
  sortDirectionFor,
  toSearchString,
  withFilterChange,
  type SortField,
  type VendorListState,
} from "../list-state";
import type { VendorSummary } from "../types";
import { VendorStatusBadge } from "./vendor-status-badge";

function SortableHead({ field, label, state }: { field: SortField; label: string; state: VendorListState }) {
  const direction = sortDirectionFor(state, field);
  const Icon = direction === "asc" ? ArrowUp : direction === "desc" ? ArrowDown : ArrowUpDown;
  const href = `/vendors${toSearchString(withFilterChange(state, { sort: nextSort(state, field) }))}`;
  return (
    <TableHead scope="col" aria-sort={ariaSortFor(state, field)}>
      <Link
        href={href}
        replace
        className="-mx-1 inline-flex items-center gap-1 rounded px-1 outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
      >
        {label}
        <Icon className="size-3.5" aria-hidden="true" />
        <span className="sr-only">, sort {direction === "asc" ? "descending" : "ascending"}</span>
      </Link>
    </TableHead>
  );
}

/** Server-rendered: sorting is plain links, so the table needs no client JS. */
export function VendorsTable({ vendors, state }: { vendors: VendorSummary[]; state: VendorListState }) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <caption className="sr-only">Vendors</caption>
        <TableHeader>
          <TableRow>
            <SortableHead field="companyName" label="Company" state={state} />
            <TableHead scope="col">Contact</TableHead>
            <TableHead scope="col">Category</TableHead>
            <TableHead scope="col" className="text-right">
              Requirements
            </TableHead>
            <TableHead scope="col">Status</TableHead>
            <SortableHead field="updatedAt" label="Updated" state={state} />
          </TableRow>
        </TableHeader>
        <TableBody>
          {vendors.map((vendor) => (
            <TableRow key={vendor.id}>
              <TableCell className="font-medium">
                <Link
                  href={`/vendors/${encodeURIComponent(vendor.id)}`}
                  className="rounded underline-offset-4 outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {vendor.companyName}
                </Link>
              </TableCell>
              <TableCell>
                {vendor.contactName || vendor.email ? (
                  <>
                    {vendor.contactName ? <div>{vendor.contactName}</div> : null}
                    {vendor.email ? <div className="text-xs text-muted-foreground">{vendor.email}</div> : null}
                  </>
                ) : (
                  <span className="text-muted-foreground">—</span>
                )}
              </TableCell>
              <TableCell>{vendor.category ?? <span className="text-muted-foreground">—</span>}</TableCell>
              <TableCell className="text-right tabular-nums">{vendor.requirementCount}</TableCell>
              <TableCell>
                <VendorStatusBadge status={vendor.status} />
              </TableCell>
              <TableCell className="text-muted-foreground">{formatDate(vendor.updatedAt)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
