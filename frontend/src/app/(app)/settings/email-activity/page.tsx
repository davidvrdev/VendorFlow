import { Mail } from "lucide-react";
import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { EmptyState } from "@/components/empty-state";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { EmailActivityTable } from "@/features/notifications/components/email-activity-table";
import { fetchNotifications } from "@/features/notifications/server";
import { can } from "@/features/organization/permissions";
import { ListPagination } from "@/features/vendors/components/list-pagination";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Email activity" };

function parsePage(value: string | string[] | undefined): number {
  const parsed = Number.parseInt((Array.isArray(value) ? value[0] : value) ?? "", 10);
  return Number.isFinite(parsed) && parsed >= 1 ? parsed : 1;
}

const hrefFor = (page: number) => `/settings/email-activity${page > 1 ? `?page=${page}` : ""}`;

export default async function EmailActivityPage({ searchParams }: PageProps<"/settings/email-activity">) {
  const page = parsePage((await searchParams).page);
  const me = await requireMe();
  // Cosmetic redirect; GET /notifications is OWNER/ADMIN-only on the API.
  if (!can(me.activeOrganization?.role, "EMAIL_ACTIVITY_VIEW")) redirect("/settings/organization");

  const result = await fetchNotifications(page).catch(() => null);

  return (
    <section aria-labelledby="email-activity-heading">
      <h2 id="email-activity-heading" className="mb-1 text-lg font-semibold tracking-tight">
        Email activity
      </h2>
      <p className="mb-4 text-sm text-muted-foreground">Emails VendorFlow sent for this organization: document requests and compliance digests.</p>
      {result === null ? (
        <Alert variant="destructive">
          <AlertTitle>Could not load email activity</AlertTitle>
          <AlertDescription>Reload the page to try again.</AlertDescription>
        </Alert>
      ) : result.items.length === 0 ? (
        <EmptyState icon={Mail} title="No emails sent yet" description="Document requests and expiration digests will appear here." />
      ) : (
        <>
          <EmailActivityTable items={result.items} />
          <ListPagination page={page} totalPages={result.totalPages} totalItems={result.totalItems} noun="email" hrefFor={hrefFor} />
        </>
      )}
    </section>
  );
}
