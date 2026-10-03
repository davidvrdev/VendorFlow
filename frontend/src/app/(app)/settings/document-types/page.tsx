import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { DocumentTypesManager } from "@/features/documents/components/document-types-manager";
import { fetchDocumentTypesAdmin } from "@/features/documents/server";
import { can } from "@/features/organization/permissions";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Document types" };

export default async function DocumentTypesSettingsPage() {
  const me = await requireMe();
  // Cosmetic redirect; GET ?includeInactive=true is also REQUIREMENTS_MANAGE-only on the API.
  if (!can(me.activeOrganization?.role, "REQUIREMENTS_MANAGE")) redirect("/settings/organization");
  const types = await fetchDocumentTypesAdmin();
  return <DocumentTypesManager types={types} />;
}
