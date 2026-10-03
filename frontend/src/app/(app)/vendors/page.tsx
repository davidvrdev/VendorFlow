import type { Metadata } from "next";
import { EmptyState } from "@/components/empty-state";
import { PageHeader } from "@/components/page-header";

export const metadata: Metadata = { title: "Vendors" };

export default function VendorsPage() {
  return (
    <>
      <PageHeader title="Vendors" />
      <EmptyState title="Coming soon" description="Vendor management is not available yet." />
    </>
  );
}
