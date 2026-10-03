import { redirect } from "next/navigation";
import { AppShell } from "@/components/app-shell";
import { requireMe } from "@/lib/auth/session";

// Authoritative session gate for everything in (app). Reads are per-user, so the whole group is dynamic.
export default async function AppLayout({ children }: LayoutProps<"/">) {
  const me = await requireMe();
  if (me.activeOrganization === null) redirect("/no-organization");
  return <AppShell me={me} activeOrganization={me.activeOrganization}>{children}</AppShell>;
}
