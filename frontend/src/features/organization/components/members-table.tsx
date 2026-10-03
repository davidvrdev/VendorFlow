"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/forms/api-errors";
import { formatDate } from "@/lib/format";
import { changeMemberRole, removeMember } from "../api";
import { assignableRoles, canManageMemberRow, ROLE_LABELS } from "../permissions";
import type { Member, Role } from "../types";

const LAST_OWNER_MESSAGE =
  "An organization needs at least one owner. Make another member an owner first, then try again.";

interface MembersTableProps {
  members: Member[];
  currentUserId: string;
  myRole: Role;
}

export function MembersTable({ members, currentUserId, myRole }: MembersTableProps) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <caption className="sr-only">Organization members</caption>
        <TableHeader>
          <TableRow>
            <TableHead scope="col">Name</TableHead>
            <TableHead scope="col">Email</TableHead>
            <TableHead scope="col">Role</TableHead>
            <TableHead scope="col">Joined</TableHead>
            <TableHead scope="col" className="text-right">
              Actions
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {members.map((member) => (
            // Re-key on role so the row's local state resets once the refreshed server data arrives.
            <MemberRow
              key={`${member.membershipId}:${member.role}`}
              member={member}
              isSelf={member.userId === currentUserId}
              myRole={myRole}
            />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function MemberRow({ member, isSelf, myRole }: { member: Member; isSelf: boolean; myRole: Role }) {
  const router = useRouter();
  const [role, setRole] = useState<Role>(member.role);
  const [changing, setChanging] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  // Own row: role is read-only (self-demotion is a footgun); the only action is leaving.
  const manageable = !isSelf && canManageMemberRow(myRole, member.role);
  const roleOptions = assignableRoles(myRole);

  async function onRoleChange(next: string) {
    const previous = role;
    setRole(next as Role);
    setChanging(true);
    try {
      await changeMemberRole(member.membershipId, next as Role);
      toast.success(`${member.fullName} is now ${ROLE_LABELS[next as Role]}.`);
      router.refresh();
    } catch (error) {
      setRole(previous);
      toast.error(errorMessage(error, { 409: LAST_OWNER_MESSAGE }));
    } finally {
      setChanging(false);
    }
  }

  async function remove() {
    await removeMember(member.membershipId);
    if (isSelf) {
      toast.success("You left the organization.");
      router.replace("/dashboard"); // the layout sends you to another organization or /no-organization
    } else {
      toast.success(`${member.fullName} was removed.`);
    }
    router.refresh();
  }

  return (
    <TableRow>
      <TableCell className="font-medium">
        {member.fullName}
        {isSelf ? <span className="ml-2 text-xs font-normal text-muted-foreground">(you)</span> : null}
      </TableCell>
      <TableCell className="text-muted-foreground">{member.email}</TableCell>
      <TableCell>
        {manageable ? (
          <Select value={role} onValueChange={onRoleChange} disabled={changing}>
            <SelectTrigger size="sm" className="w-32" aria-label={`Role for ${member.fullName}`}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {roleOptions.map((option) => (
                <SelectItem key={option} value={option}>
                  {ROLE_LABELS[option]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        ) : (
          ROLE_LABELS[member.role]
        )}
      </TableCell>
      <TableCell className="text-muted-foreground">{formatDate(member.joinedAt)}</TableCell>
      <TableCell className="text-right">
        {manageable || isSelf ? (
          <>
            <Button variant="outline" size="sm" onClick={() => setConfirmOpen(true)}>
              {isSelf ? "Leave organization" : "Remove"}
              <span className="sr-only">{isSelf ? "" : ` ${member.fullName}`}</span>
            </Button>
            <ConfirmDialog
              open={confirmOpen}
              onOpenChange={setConfirmOpen}
              title={isSelf ? "Leave this organization?" : `Remove ${member.fullName}?`}
              description={
                isSelf
                  ? "You will lose access to this organization's vendors and documents. An owner would need to invite you again."
                  : `${member.fullName} will lose access to this organization immediately.`
              }
              confirmLabel={isSelf ? "Leave organization" : "Remove member"}
              pendingLabel={isSelf ? "Leaving…" : "Removing…"}
              onConfirm={remove}
              describeError={(error) => errorMessage(error, { 409: LAST_OWNER_MESSAGE })}
            />
          </>
        ) : null}
      </TableCell>
    </TableRow>
  );
}
