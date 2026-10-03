import type { Metadata } from "next";
import { InvitationsSection } from "@/features/organization/components/invitations-section";
import { MembersTable } from "@/features/organization/components/members-table";
import { can } from "@/features/organization/permissions";
import { fetchInvitations, fetchMembers } from "@/features/organization/server";
import { requireMe } from "@/lib/auth/session";

export const metadata: Metadata = { title: "Members" };

export default async function MembersSettingsPage() {
  const me = await requireMe();
  const role = me.activeOrganization?.role ?? "VIEWER";
  const canManage = can(role, "MEMBERS_MANAGE");
  // Invitations are only listed for roles that may manage them; everyone sees members.
  const [members, invitations] = await Promise.all([fetchMembers(), canManage ? fetchInvitations() : Promise.resolve(null)]);

  return (
    <>
      <section aria-labelledby="members-heading">
        <h2 id="members-heading" className="mb-4 text-lg font-semibold tracking-tight">
          Members
        </h2>
        <MembersTable members={members.items} totalItems={members.totalItems} currentUserId={me.user.id} myRole={role} />
      </section>
      {invitations ? <InvitationsSection invitations={invitations.items} totalItems={invitations.totalItems} myRole={role} /> : null}
    </>
  );
}
