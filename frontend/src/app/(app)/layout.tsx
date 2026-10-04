import { redirect } from "next/navigation";
import { AppShell } from "@/components/app-shell";
import { fetchSubscriptionOrNull } from "@/features/billing/server";
import { requireMe } from "@/lib/auth/session";

// Authoritative session gate for everything in (app). Reads are per-user, so the whole group is dynamic.
export default async function AppLayout({ children }: LayoutProps<"/">) {
  const me = await requireMe();
  if (me.activeOrganization === null) redirect("/no-organization");
  const subscription = await fetchSubscriptionOrNull();
  return (
    <AppShell me={me} activeOrganization={me.activeOrganization} subscription={subscription}>
      {children}
    </AppShell>
  );
}
