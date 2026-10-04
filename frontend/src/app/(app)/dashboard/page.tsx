import { CheckCircle2, Store } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { EmptyState } from "@/components/empty-state";
import { PageHeader } from "@/components/page-header";
import { buttonVariants } from "@/components/ui/button";
import { AttentionList } from "@/features/dashboard/components/attention-list";
import { SummaryTiles } from "@/features/dashboard/components/summary-tiles";
import { fetchAttention, fetchDashboardSummary } from "@/features/dashboard/server";
import { attentionHref, dashboardState, parseAttentionPage } from "@/features/dashboard/view";
import { can } from "@/features/organization/permissions";
import { ListPagination } from "@/features/vendors/components/list-pagination";
import { fetchDocumentTypes } from "@/features/vendors/server";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Dashboard" };

export default async function DashboardPage({ searchParams }: PageProps<"/dashboard">) {
  const page = parseAttentionPage((await searchParams).attentionPage);
  const me = await requireMe();
  const role = me.activeOrganization?.role;

  const [summary, attention] = await Promise.all([fetchDashboardSummary(), fetchAttention(page)]);

  // Stale link past the last page (items resolved since): go to the last one.
  if (attention.items.length === 0 && attention.totalItems > 0 && page > 1) {
    redirect(attentionHref(Math.max(attention.totalPages, 1)));
  }

  const state = dashboardState(summary, attention.totalItems);
  const canWrite = can(role, "VENDORS_WRITE");
  // Only writers get the upload dialog, so only they need the types.
  const documentTypes = state === "attention" && canWrite ? await fetchDocumentTypes() : [];

  return (
    <>
      <PageHeader title="Dashboard" description="Which vendors need your attention right now." />
      {state === "no-vendors" ? (
        <EmptyState
          icon={Store}
          title="Add your first vendor"
          description="Add a vendor and VendorFlow shows what is missing, what is expiring and who to chase."
        >
          {canWrite ? (
            <Link href="/vendors/new" className={buttonVariants()}>
              Add your first vendor
            </Link>
          ) : null}
          {can(role, "ARCHIVE_AND_IMPORT") ? (
            <Link href="/vendors/import" className={buttonVariants({ variant: "outline" })}>
              Import CSV
            </Link>
          ) : null}
        </EmptyState>
      ) : (
        <>
          <SummaryTiles summary={summary} />
          <section aria-labelledby="attention-heading">
            <h2 id="attention-heading" className="mb-3 text-lg font-semibold">
              Needs attention
            </h2>
            {state === "all-clear" ? (
              <EmptyState icon={CheckCircle2} title="All active vendors are compliant." description={`As of ${summary.today}.`} />
            ) : (
              <>
                <AttentionList items={attention.items} role={role} documentTypes={documentTypes} />
                <ListPagination
                  page={page}
                  totalPages={attention.totalPages}
                  totalItems={attention.totalItems}
                  noun="item"
                  hrefFor={attentionHref}
                />
              </>
            )}
          </section>
        </>
      )}
    </>
  );
}
