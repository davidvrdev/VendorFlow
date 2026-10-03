import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { PageHeader } from "@/components/page-header";
import { can } from "@/features/organization/permissions";
import { VendorForm } from "@/features/vendors/components/vendor-form";
import { fetchCategories, fetchVendorOrNotFound } from "@/features/vendors/server";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Edit vendor" };

export default async function EditVendorPage({ params }: PageProps<"/vendors/[id]/edit">) {
  const { id } = await params;
  const me = await requireMe();
  if (!can(me.activeOrganization?.role, "VENDORS_WRITE")) redirect(`/vendors/${encodeURIComponent(id)}`);
  const [vendor, categories] = await Promise.all([fetchVendorOrNotFound(id), fetchCategories()]);

  return (
    <>
      <PageHeader title={`Edit ${vendor.companyName}`} />
      <VendorForm vendor={vendor} categories={categories} />
    </>
  );
}
