import type { ReactNode } from "react";
import { PageHeader } from "@/components/page-header";
import { SettingsNav } from "@/features/organization/components/settings-nav";
import { can } from "@/features/organization/permissions";
import { requireMe } from "@/lib/auth/session";

export default async function SettingsLayout({ children }: { children: ReactNode }) {
  const me = await requireMe(); // cached per request: the (app) layout already made this call
  const canManageDocumentTypes = can(me.activeOrganization?.role, "REQUIREMENTS_MANAGE");
  return (
    <>
      <PageHeader title="Settings" description="Manage your organization, team and invitations." />
      <SettingsNav canManageDocumentTypes={canManageDocumentTypes} />
      {children}
    </>
  );
}
