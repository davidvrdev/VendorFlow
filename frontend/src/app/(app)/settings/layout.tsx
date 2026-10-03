import type { ReactNode } from "react";
import { PageHeader } from "@/components/page-header";
import { SettingsNav } from "@/features/organization/components/settings-nav";
import { requireMe } from "@/lib/auth/session";

export default async function SettingsLayout({ children }: { children: ReactNode }) {
  const me = await requireMe(); // cached per request: the (app) layout already made this call
  return (
    <>
      <PageHeader title="Settings" description="Manage your organization, team and invitations." />
      <SettingsNav role={me.activeOrganization?.role} />
      {children}
    </>
  );
}
