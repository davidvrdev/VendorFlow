import { ChevronLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { PageHeader } from "@/components/page-header";
import { DocumentHistory } from "@/features/documents/components/document-history";
import { OtherDocumentsCard } from "@/features/documents/components/other-documents-card";
import { HistoryCard } from "@/features/vendors/components/history-card";
import { currentDocumentsByType, RequirementsCard } from "@/features/vendors/components/requirements-card";
import { VendorActions } from "@/features/vendors/components/vendor-actions";
import { VendorDetailsCard } from "@/features/vendors/components/vendor-details-card";
import { VendorStatusBadge } from "@/features/vendors/components/vendor-status-badge";
import { asNotFound, fetchDocumentTypes, fetchVendorHistory, fetchVendorOrNotFound } from "@/features/vendors/server";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Vendor" };

function parsePage(value: string | string[] | undefined): number {
  const parsed = Number.parseInt((Array.isArray(value) ? value[0] : value) ?? "", 10);
  return Number.isFinite(parsed) && parsed >= 1 ? parsed : 1;
}

export default async function VendorDetailPage({ params, searchParams }: PageProps<"/vendors/[id]">) {
  const [{ id }, query] = await Promise.all([params, searchParams]);
  const historyPage = parsePage(query.historyPage);
  const me = await requireMe();
  const role = me.activeOrganization?.role ?? "VIEWER";

  const [vendor, history, documentTypes] = await Promise.all([
    fetchVendorOrNotFound(id),
    fetchVendorHistory(id, historyPage).catch((error: unknown) => {
      throw asNotFound(error);
    }),
    fetchDocumentTypes(),
  ]);

  if (history.items.length === 0 && history.totalItems > 0 && historyPage > 1) {
    redirect(`/vendors/${encodeURIComponent(id)}?historyPage=${Math.max(history.totalPages, 1)}#history`);
  }

  // Changes whenever the set of current documents changes, so an open history list reloads after uploads/archives.
  const documentsVersion = [...vendor.requirements.flatMap((r) => (r.currentDocument ? [r.currentDocument.id] : [])), ...vendor.otherDocuments.map((d) => d.id)].join(",");
  const typeNames = Object.fromEntries(documentTypes.map((type) => [type.code, type.name]));

  return (
    <>
      <Link href="/vendors" className="mb-4 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ChevronLeft className="size-4" aria-hidden="true" />
        Vendors
      </Link>
      <PageHeader title={vendor.companyName} actions={<VendorActions vendor={vendor} role={role} />} />
      <div className="-mt-6 mb-8">
        <VendorStatusBadge status={vendor.status} />
      </div>
      <div className="grid max-w-4xl gap-6">
        <VendorDetailsCard vendor={vendor} />
        <RequirementsCard vendor={vendor} documentTypes={documentTypes} role={role} />
        <OtherDocumentsCard vendor={vendor} documentTypes={documentTypes} currentByType={currentDocumentsByType(vendor)} role={role} />
        <DocumentHistory vendorId={vendor.id} version={documentsVersion} />
        <HistoryCard
          events={history.items}
          typeNames={typeNames}
          page={historyPage}
          totalPages={history.totalPages}
          totalItems={history.totalItems}
          hrefFor={(page) => `/vendors/${encodeURIComponent(id)}${page > 1 ? `?historyPage=${page}` : ""}#history`}
        />
      </div>
    </>
  );
}
