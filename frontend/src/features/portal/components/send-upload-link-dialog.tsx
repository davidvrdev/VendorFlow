"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { Check, Copy, Link2 } from "lucide-react";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { TextField } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { ComplianceStatus } from "@/features/compliance/types";
import { applyApiError } from "@/lib/forms/api-errors";
import { createUploadLink } from "../api";
import { createLinkStatusMessages } from "../errors";
import { sendLinkFormSchema, type CreatedUploadLink, type SendLinkFormOutput, type SendLinkFormValues } from "../schemas";

export interface LinkRequirement {
  documentTypeId: string;
  name: string;
  status: ComplianceStatus;
}

interface SendUploadLinkDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  vendorId: string;
  vendorName: string;
  vendorEmail: string | null | undefined;
  /** Active requirements of the vendor: the only types a link can ask for. */
  requirements: LinkRequirement[];
  /** Called once a link exists, so the list can reload. */
  onCreated: () => void;
}

const NEEDS_ATTENTION: ComplianceStatus[] = ["MISSING", "EXPIRING", "EXPIRED"];

/** Dialog with two steps: the form, then the one-time URL. The form is mounted only while open so each opening starts clean. */
export function SendUploadLinkDialog({ open, onOpenChange, ...rest }: SendUploadLinkDialogProps) {
  const [pending, setPending] = useState(false);
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (pending) return;
        onOpenChange(next);
      }}
    >
      <DialogContent>
        <Body {...rest} onPendingChange={setPending} onClose={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

type BodyProps = Omit<SendUploadLinkDialogProps, "open" | "onOpenChange"> & { onPendingChange: (pending: boolean) => void; onClose: () => void };

function Body({ vendorId, vendorName, vendorEmail, requirements, onCreated, onPendingChange, onClose }: BodyProps) {
  const [created, setCreated] = useState<CreatedUploadLink | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const hasEmail = Boolean(vendorEmail);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SendLinkFormValues, unknown, SendLinkFormOutput>({
    resolver: zodResolver(sendLinkFormSchema),
    defaultValues: {
      documentTypeIds: requirements.filter((r) => NEEDS_ATTENTION.includes(r.status)).map((r) => r.documentTypeId),
      expiresInDays: "14",
      maxUploads: "20",
      sendEmail: false,
    },
  });

  async function onSubmit(values: SendLinkFormOutput) {
    setFormError(null);
    onPendingChange(true);
    try {
      const result = await createUploadLink(vendorId, { ...values, sendEmail: hasEmail && values.sendEmail });
      setCreated(result);
      onCreated();
    } catch (error) {
      setFormError(
        applyApiError<SendLinkFormValues>(error, setError, {
          fields: ["documentTypeIds", "expiresInDays", "maxUploads"],
          statusMessages: createLinkStatusMessages(error),
        }),
      );
    } finally {
      onPendingChange(false);
    }
  }

  if (created) return <CreatedView created={created} vendorName={vendorName} onClose={onClose} />;

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Send upload link</DialogTitle>
        <DialogDescription>
          Create a private link {vendorName} can use to upload documents without an account.
        </DialogDescription>
      </DialogHeader>
      <FormAlert message={formError} />

      <fieldset className="grid gap-2" aria-describedby={errors.documentTypeIds ? "link-types-error" : undefined}>
        <legend className="mb-1 text-sm font-medium">Documents to request</legend>
        {requirements.length === 0 ? (
          <p className="text-sm text-muted-foreground">This vendor has no active required documents.</p>
        ) : (
          <Controller
            control={control}
            name="documentTypeIds"
            render={({ field }) => (
              <>
                {requirements.map((requirement) => {
                  const id = `link-type-${requirement.documentTypeId}`;
                  const checked = field.value.includes(requirement.documentTypeId);
                  return (
                    <div key={requirement.documentTypeId} className="flex items-center gap-2">
                      <Checkbox
                        id={id}
                        checked={checked}
                        disabled={isSubmitting}
                        onCheckedChange={(next) =>
                          field.onChange(next === true ? [...field.value, requirement.documentTypeId] : field.value.filter((v) => v !== requirement.documentTypeId))
                        }
                      />
                      <Label htmlFor={id} className="font-normal">
                        {requirement.name}
                      </Label>
                    </div>
                  );
                })}
              </>
            )}
          />
        )}
        {errors.documentTypeIds?.message ? (
          <p id="link-types-error" className="text-xs font-medium text-destructive">
            {errors.documentTypeIds.message}
          </p>
        ) : null}
      </fieldset>

      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          id="link-expires"
          label="Expires in (days)"
          type="number"
          inputMode="numeric"
          min={1}
          max={30}
          disabled={isSubmitting}
          help="1 to 30 days."
          error={errors.expiresInDays?.message}
          {...register("expiresInDays")}
        />
        <TextField
          id="link-max"
          label="Maximum uploads"
          type="number"
          inputMode="numeric"
          min={1}
          max={50}
          disabled={isSubmitting}
          help="1 to 50 files in total."
          error={errors.maxUploads?.message}
          {...register("maxUploads")}
        />
      </div>

      <div className="grid gap-1">
        <div className="flex items-center gap-2">
          <Controller
            control={control}
            name="sendEmail"
            render={({ field }) => (
              <Checkbox
                id="link-send-email"
                checked={field.value && hasEmail}
                disabled={!hasEmail || isSubmitting}
                aria-describedby={hasEmail ? undefined : "link-email-help"}
                onCheckedChange={(next) => field.onChange(next === true)}
              />
            )}
          />
          <Label htmlFor="link-send-email" className="font-normal">
            {hasEmail ? `Email the link to ${vendorEmail}` : "Email the link to the vendor"}
          </Label>
        </div>
        {!hasEmail ? (
          <p id="link-email-help" className="text-xs text-muted-foreground">
            This vendor has no email address. Add one in the vendor details, or copy the link and send it yourself.
          </p>
        ) : null}
      </div>

      <DialogFooter>
        <Button type="button" variant="outline" disabled={isSubmitting} onClick={onClose}>
          Cancel
        </Button>
        <SubmitButton pending={isSubmitting} pendingLabel="Creating…" disabled={requirements.length === 0}>
          Create link
        </SubmitButton>
      </DialogFooter>
    </form>
  );
}

function CreatedView({ created, vendorName, onClose }: { created: CreatedUploadLink; vendorName: string; onClose: () => void }) {
  const [copyState, setCopyState] = useState<"idle" | "copied" | "failed">("idle");

  async function copy() {
    try {
      await navigator.clipboard.writeText(created.url);
      setCopyState("copied");
    } catch {
      setCopyState("failed");
    }
  }

  return (
    <div className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Upload link created</DialogTitle>
        <DialogDescription>
          <Link2 className="mr-1 inline size-4" aria-hidden="true" />
          Share this link with {vendorName}. Anyone with the link can upload the requested documents.
        </DialogDescription>
      </DialogHeader>
      <div className="grid gap-1.5">
        <Label htmlFor="created-link-url">Upload link</Label>
        <div className="flex gap-2">
          <Input id="created-link-url" readOnly value={created.url} onFocus={(event) => event.currentTarget.select()} />
          <Button type="button" variant="outline" onClick={() => void copy()}>
            {copyState === "copied" ? <Check aria-hidden="true" data-icon="inline-start" /> : <Copy aria-hidden="true" data-icon="inline-start" />}
            {copyState === "copied" ? "Copied" : "Copy link"}
          </Button>
        </div>
        <p role="status" className="text-xs text-muted-foreground">
          {copyState === "failed" ? "Could not copy automatically. Select the link above and copy it manually." : copyState === "copied" ? "Link copied to the clipboard." : ""}
        </p>
      </div>
      <p className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200">
        This link is shown only once. If you lose it, revoke it and create a new one.
      </p>
      <p className="text-sm text-muted-foreground">
        {created.emailQueued ? "An email with the link is on its way to the vendor." : "No email was sent."}
      </p>
      <DialogFooter>
        <Button type="button" onClick={onClose}>
          Done
        </Button>
      </DialogFooter>
    </div>
  );
}
