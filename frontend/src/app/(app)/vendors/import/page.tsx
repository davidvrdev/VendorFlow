import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { PageHeader } from "@/components/page-header";
import { ImportWizard } from "@/features/import/components/import-wizard";
import { can } from "@/features/organization/permissions";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Import vendors" };

export default async function ImportVendorsPage() {
  const me = await requireMe();
  // Cosmetic gate: the backend still enforces ARCHIVE_AND_IMPORT on every import endpoint.
  if (!can(me.activeOrganization?.role, "ARCHIVE_AND_IMPORT")) redirect("/vendors");

  return (
    <>
      <PageHeader title="Import vendors" description="Create and update vendors from a CSV file. You review a preview before anything changes." />
      <ImportWizard />
    </>
  );
}
