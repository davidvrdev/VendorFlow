"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { MailPlus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { EmptyState } from "@/components/empty-state";
import { FieldShell, TextField } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { applyApiError, errorMessage } from "@/lib/forms/api-errors";
import { formatDate } from "@/lib/format";
import { ListCount } from "./list-count";
import { createInvitation, revokeInvitation } from "../api";
import { invitableRoles, ROLE_LABELS } from "../permissions";
import { inviteFormSchema, type InviteFormValues } from "../schemas";
import type { Invitation, Role } from "../types";

export function InvitationsSection({
  invitations,
  totalItems,
  myRole,
}: {
  invitations: Invitation[];
  totalItems: number;
  myRole: Role;
}) {
  return (
    <section aria-labelledby="invitations-heading" className="mt-12">
      <h2 id="invitations-heading" className="text-lg font-semibold tracking-tight">
        Invitations
      </h2>
      <p className="mt-1 mb-4 text-sm text-muted-foreground">Invite teammates by email. Invitations expire after 7 days.</p>
      <InviteForm myRole={myRole} />
      <div className="mt-6">
        {invitations.length === 0 ? (
          <EmptyState icon={MailPlus} title="No pending invitations" description="Invitations you send will appear here until they are accepted or expire." />
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <caption className="sr-only">Pending invitations</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">Email</TableHead>
                  <TableHead scope="col">Role</TableHead>
                  <TableHead scope="col">Expires</TableHead>
                  <TableHead scope="col" className="text-right">
                    Actions
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {invitations.map((invitation) => (
                  <InvitationRow key={invitation.id} invitation={invitation} />
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <ListCount shown={invitations.length} total={totalItems} />
      </div>
    </section>
  );
}

function InviteForm({ myRole }: { myRole: Role }) {
  const router = useRouter();
  const roles = invitableRoles(myRole);
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    control,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<InviteFormValues>({
    resolver: zodResolver(inviteFormSchema),
    defaultValues: { email: "", role: "MEMBER" },
  });

  async function onSubmit(values: InviteFormValues) {
    setFormError(null);
    try {
      await createInvitation(values);
      toast.success(`Invitation sent to ${values.email}.`);
      reset({ email: "", role: values.role });
      router.refresh();
    } catch (error) {
      setFormError(
        applyApiError<InviteFormValues>(error, setError, {
          fields: ["email", "role"],
          // 409 and 422 carry specific titles ("Already a member", "Invitation already pending",
          // "Email not verified"): ApiError.detail/title is shown by applyApiError. Only 422 needs a hint.
          statusMessages: {
            422: "Verify your own email address before inviting teammates. Use Resend email in the banner above.",
            429: "Too many attempts. Try again in a minute.",
          },
        }),
      );
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid max-w-2xl gap-4 rounded-lg border p-4">
      <FormAlert message={formError} />
      <div className="grid gap-4 sm:grid-cols-[1fr_12rem] sm:items-start">
        <TextField id="invite-email" label="Email" type="email" autoComplete="off" error={errors.email?.message} {...register("email")} />
        <FieldShell id="invite-role" label="Role" error={errors.role?.message}>
          {(aria) => (
            <Controller
              control={control}
              name="role"
              render={({ field }) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger {...aria} className="w-full" onBlur={field.onBlur}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {roles.map((role) => (
                      <SelectItem key={role} value={role}>
                        {ROLE_LABELS[role]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            />
          )}
        </FieldShell>
      </div>
      <div>
        <SubmitButton pending={isSubmitting} pendingLabel="Sending…">
          Send invitation
        </SubmitButton>
      </div>
    </form>
  );
}

function InvitationRow({ invitation }: { invitation: Invitation }) {
  const router = useRouter();
  const [confirmOpen, setConfirmOpen] = useState(false);

  async function revoke() {
    await revokeInvitation(invitation.id);
    toast.success(`Invitation for ${invitation.email} was revoked.`);
    router.refresh();
  }

  return (
    <TableRow>
      <TableCell className="font-medium">{invitation.email}</TableCell>
      <TableCell>{ROLE_LABELS[invitation.role]}</TableCell>
      <TableCell className="text-muted-foreground">{formatDate(invitation.expiresAt)}</TableCell>
      <TableCell className="text-right">
        <Button variant="outline" size="sm" onClick={() => setConfirmOpen(true)}>
          Revoke<span className="sr-only"> invitation for {invitation.email}</span>
        </Button>
        <ConfirmDialog
          open={confirmOpen}
          onOpenChange={setConfirmOpen}
          title="Revoke this invitation?"
          description={`${invitation.email} will no longer be able to join with this invitation.`}
          confirmLabel="Revoke invitation"
          pendingLabel="Revoking…"
          onConfirm={revoke}
          describeError={(error) => errorMessage(error)}
        />
      </TableCell>
    </TableRow>
  );
}
