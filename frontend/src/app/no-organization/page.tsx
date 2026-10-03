import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { SignOutButton } from "@/features/auth/components/sign-out-button";
import { EmptyState } from "@/components/empty-state";
import { SwitchOrganizationList } from "@/features/organization/components/switch-organization-list";
import { getMe } from "@/lib/auth/session";
import { Building2 } from "lucide-react";

export const metadata: Metadata = { title: "No organization" };

export default async function NoOrganizationPage() {
  const me = await getMe();
  if (!me) redirect("/login");
  if (me.activeOrganization) redirect("/dashboard");
  return (
    <main id="main" tabIndex={-1} className="mx-auto flex w-full max-w-lg flex-1 flex-col justify-center px-4 py-16 outline-none">
      <h1 className="sr-only">No organization access</h1>
      {me.organizations.length > 0 ? (
        <EmptyState
          icon={Building2}
          title="Choose an organization"
          description="You are not working in an organization right now (for example after leaving one). Pick one of your other organizations to continue."
        >
          <SwitchOrganizationList organizations={me.organizations} />
          <SignOutButton variant="outline" />
        </EmptyState>
      ) : (
        <EmptyState
          icon={Building2}
          title="You do not have access to an organization"
          description={`You are signed in as ${me.user.email}, but this account is not a member of any organization. Ask an owner to invite you, or sign out and use a different account.`}
        >
          <SignOutButton variant="outline" />
        </EmptyState>
      )}
    </main>
  );
}
