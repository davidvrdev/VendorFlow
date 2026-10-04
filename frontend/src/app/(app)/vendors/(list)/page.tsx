import { SearchX, Store } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { EmptyState } from "@/components/empty-state";
import { PageHeader } from "@/components/page-header";
import { buttonVariants } from "@/components/ui/button";
import { can } from "@/features/organization/permissions";
import { ListPagination } from "@/features/vendors/components/list-pagination";
import { VendorToolbar } from "@/features/vendors/components/vendor-toolbar";
import { VendorsTable } from "@/features/vendors/components/vendors-table";
import { hasDefaultFilters, parseListState, toSearchString, withPage } from "@/features/vendors/list-state";
import { fetchCategories, fetchVendors } from "@/features/vendors/server";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Vendors" };

export default async function VendorsPage({ searchParams }: PageProps<"/vendors">) {
  const state = parseListState(await searchParams);
  const me = await requireMe();
  const canCreate = can(me.activeOrganization?.role, "VENDORS_WRITE");
  const canImport = can(me.activeOrganization?.role, "ARCHIVE_AND_IMPORT");

  const [result, categories] = await Promise.all([fetchVendors(state), fetchCategories()]);

  // A stale link past the last page (e.g. after deactivating the only vendor on page 2): go to the last page.
  if (result.items.length === 0 && result.totalItems > 0 && state.page > 1) {
    redirect(`/vendors${toSearchString(withPage(state, Math.max(result.totalPages, 1)))}`);
  }

  // Default filters show only ACTIVE vendors, so an empty result is "no vendors at all" only if no inactive ones exist either.
  const defaultEmpty = result.totalItems === 0 && hasDefaultFilters(state);
  const inactiveExist = defaultEmpty && (await fetchVendors({ ...state, status: "INACTIVE" })).totalItems > 0;
  const noVendorsAtAll = defaultEmpty && !inactiveExist;

  return (
    <>
      <PageHeader title="Vendors" description="Companies you collect compliance documents from." />
      {noVendorsAtAll ? (
        <EmptyState
          icon={Store}
          title="No vendors yet"
          description="Add your first vendor to start tracking the documents they owe you."
        >
          {canCreate ? (
            <Link href="/vendors/new" className={buttonVariants()}>
              Add vendor
            </Link>
          ) : null}
          {canImport ? (
            <Link href="/vendors/import" className={buttonVariants({ variant: "outline" })}>
              Import CSV
            </Link>
          ) : null}
        </EmptyState>
      ) : (
        <>
          <VendorToolbar state={state} categories={categories} canCreate={canCreate} canImport={canImport} totalItems={result.totalItems} />
          {inactiveExist ? (
            <EmptyState icon={SearchX} title="No active vendors" description="All of your vendors are inactive.">
              <Link href="/vendors?status=INACTIVE" className={buttonVariants({ variant: "outline" })}>
                Show inactive vendors
              </Link>
            </EmptyState>
          ) : result.totalItems === 0 ? (
            <EmptyState icon={SearchX} title="No vendors match your filters" description="Try a different search, or clear the filters.">
              <Link href="/vendors" className={buttonVariants({ variant: "outline" })}>
                Clear filters
              </Link>
            </EmptyState>
          ) : (
            <>
              <VendorsTable vendors={result.items} state={state} />
              <ListPagination
                page={state.page}
                totalPages={result.totalPages}
                totalItems={result.totalItems}
                noun="vendor"
                hrefFor={(page) => `/vendors${toSearchString(withPage(state, page))}`}
              />
            </>
          )}
        </>
      )}
    </>
  );
}
