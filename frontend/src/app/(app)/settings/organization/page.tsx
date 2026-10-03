import type { Metadata } from "next";
import { OrganizationForm } from "@/features/organization/components/organization-form";
import { can } from "@/features/organization/permissions";
import { fetchOrganization } from "@/features/organization/server";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Organization settings" };

export default async function OrganizationSettingsPage() {
  const [me, organization] = await Promise.all([requireMe(), fetchOrganization()]);
  // Cosmetic: the backend enforces ORG_SETTINGS_MANAGE on PATCH regardless.
  const canEdit = can(me.activeOrganization?.role, "ORG_SETTINGS_MANAGE");
  // key: remount with fresh defaults if the organization (or switched org) changes after router.refresh().
  return <OrganizationForm key={organization.id} organization={organization} canEdit={canEdit} />;
}
