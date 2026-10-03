import type { ReactNode } from "react";
import { PageHeader } from "@/components/page-header";
import { SettingsNav } from "@/features/organization/components/settings-nav";

export default function SettingsLayout({ children }: { children: ReactNode }) {
  return (
    <>
      <PageHeader title="Settings" description="Manage your organization, team and invitations." />
      <SettingsNav />
      {children}
    </>
  );
}
