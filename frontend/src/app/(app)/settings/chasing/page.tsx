import type { Metadata } from "next";
import { ChasingSettingsForm } from "@/features/chasing/components/chasing-settings-form";
import { fetchChasingSettings } from "@/features/chasing/server";
import { can } from "@/features/organization/permissions";
import { fetchOrganization } from "@/features/organization/server";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Vendor follow-ups" };

export default async function ChasingSettingsPage() {
  const [me, settings, organization] = await Promise.all([requireMe(), fetchChasingSettings(), fetchOrganization()]);
  // Cosmetic: the backend enforces ORG_SETTINGS_MANAGE on PUT regardless.
  const canEdit = can(me.activeOrganization?.role, "ORG_SETTINGS_MANAGE");
  // key: remount with fresh defaults after router.refresh() or an organization switch.
  return <ChasingSettingsForm key={`${organization.id}:${JSON.stringify(settings)}`} settings={settings} timeZone={organization.timeZone} canEdit={canEdit} />;
}
