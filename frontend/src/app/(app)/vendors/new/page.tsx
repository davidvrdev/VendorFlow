import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { PageHeader } from "@/components/page-header";
import { can } from "@/features/organization/permissions";
import { VendorForm } from "@/features/vendors/components/vendor-form";
import { fetchCategories } from "@/features/vendors/server";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Add vendor" };

export default async function NewVendorPage() {
  const me = await requireMe();
  // Cosmetic gate: the backend still enforces VENDORS_WRITE on POST /vendors.
  if (!can(me.activeOrganization?.role, "VENDORS_WRITE")) redirect("/vendors");
  const categories = await fetchCategories();

  return (
    <>
      <PageHeader title="Add vendor" description="You can choose the documents they must provide after saving." />
      <VendorForm categories={categories} />
    </>
  );
}
