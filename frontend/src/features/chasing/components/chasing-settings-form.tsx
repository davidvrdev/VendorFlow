"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { toast } from "sonner";
import { TextField } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { applyApiError } from "@/lib/forms/api-errors";
import { saveChasingSettings } from "../api";
import {
  ATTEMPTS_RANGE,
  CADENCE_RANGE,
  chasingSettingsFormSchema,
  LEAD_RANGE,
  toChasingFormValues,
  toChasingRequest,
  type ChasingSettings,
  type ChasingSettingsFormValues,
} from "../schemas";

const FIELDS = ["enabled", "cadenceDays", "maxAttempts", "leadDays", "sendHourLocal", "ccStaff"] as const;

interface ChasingSettingsFormProps {
  settings: ChasingSettings;
  timeZone: string;
  /** Cosmetic: the backend enforces ORG_SETTINGS_MANAGE on PUT. */
  canEdit: boolean;
}

export function ChasingSettingsForm({ settings, timeZone, canEdit }: ChasingSettingsFormProps) {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    control,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<ChasingSettingsFormValues>({
    resolver: zodResolver(chasingSettingsFormSchema),
    defaultValues: toChasingFormValues(settings),
  });

  async function onSubmit(values: ChasingSettingsFormValues) {
    setFormError(null);
    try {
      const saved = await saveChasingSettings(toChasingRequest(values));
      reset(toChasingFormValues(saved));
      toast.success("Follow-up settings saved.");
      router.refresh();
    } catch (error) {
      setFormError(
        applyApiError<ChasingSettingsFormValues>(error, setError, {
          fields: FIELDS,
          statusMessages: {
            402: "Your subscription is inactive. Subscribe to change settings.",
            403: "You do not have permission to change follow-up settings.",
          },
        }),
      );
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid max-w-xl gap-5" aria-label="Vendor follow-up settings">
      <section className="grid gap-2 rounded-lg border bg-muted/40 p-4 text-sm" aria-labelledby="chasing-explainer">
        <h2 id="chasing-explainer" className="font-semibold">
          What vendors receive
        </h2>
        <p className="text-muted-foreground">
          When a vendor has a missing, expired or soon-to-expire required document, VendorFlow emails the vendor a request that lists
          what is needed, a private upload link and a link to stop these reminders. It repeats on the schedule below until the vendor
          is compliant or the maximum is reached. At most one email per vendor per day is sent. Vendors without an email address,
          inactive vendors and paused vendors are skipped.
        </p>
        <p className="text-muted-foreground">
          A vendor who uses the unsubscribe link is paused automatically, and staff cannot turn their reminders back on. You can still
          request documents manually. You can also pause a single vendor from its page.
        </p>
      </section>

      {canEdit ? null : (
        <p id="chasing-readonly-note" className="rounded-md border bg-muted px-3 py-2 text-sm text-muted-foreground">
          Only owners and admins can change follow-up settings.
        </p>
      )}
      <FormAlert message={formError} />

      <div className="flex items-start gap-2">
        <Controller
          control={control}
          name="enabled"
          render={({ field }) => (
            <Checkbox
              id="enabled"
              checked={field.value}
              onCheckedChange={(checked) => field.onChange(checked === true)}
              disabled={!canEdit}
              aria-describedby="enabled-help"
            />
          )}
        />
        <div className="grid gap-1">
          <Label htmlFor="enabled">Send automatic follow-ups to vendors</Label>
          <p id="enabled-help" className="text-xs text-muted-foreground">
            Off by default. Nothing is emailed to vendors until you turn this on.
          </p>
        </div>
      </div>

      <TextField
        id="cadenceDays"
        label="Days between follow-ups"
        inputMode="numeric"
        disabled={!canEdit}
        help={`A vendor is emailed at most once every this many days (${CADENCE_RANGE.min} to ${CADENCE_RANGE.max}).`}
        error={errors.cadenceDays?.message}
        {...register("cadenceDays")}
      />
      <TextField
        id="maxAttempts"
        label="Maximum follow-ups per vendor"
        inputMode="numeric"
        disabled={!canEdit}
        help={`After this many emails with no fix, owners and admins are told once and the vendor is no longer chased (${ATTEMPTS_RANGE.min} to ${ATTEMPTS_RANGE.max}).`}
        error={errors.maxAttempts?.message}
        {...register("maxAttempts")}
      />
      <TextField
        id="leadDays"
        label="Start chasing this many days before expiry"
        inputMode="numeric"
        disabled={!canEdit}
        help={`Documents expiring within this window count as needing follow-up (${LEAD_RANGE.min} to ${LEAD_RANGE.max}).`}
        error={errors.leadDays?.message}
        {...register("leadDays")}
      />
      <TextField
        id="sendHourLocal"
        label="Send hour (0 to 23)"
        inputMode="numeric"
        disabled={!canEdit}
        help={`24-hour clock in your organization's time zone: ${timeZone}. Emails go out at or after this hour, for example 9 is 9:00 AM. Change the time zone under Organization.`}
        error={errors.sendHourLocal?.message}
        {...register("sendHourLocal")}
      />

      <div className="flex items-start gap-2">
        <Controller
          control={control}
          name="ccStaff"
          render={({ field }) => (
            <Checkbox
              id="ccStaff"
              checked={field.value}
              onCheckedChange={(checked) => field.onChange(checked === true)}
              disabled={!canEdit}
              aria-describedby="ccStaff-help"
            />
          )}
        />
        <div className="grid gap-1">
          <Label htmlFor="ccStaff">Email owners and admins a daily summary</Label>
          <p id="ccStaff-help" className="text-xs text-muted-foreground">
            One email per day listing which vendors were followed up and for what. It contains no upload links. Owners and admins are
            always notified when a vendor runs out of follow-ups or unsubscribes, whatever this says.
          </p>
        </div>
      </div>

      {canEdit ? (
        <div>
          <SubmitButton pending={isSubmitting} pendingLabel="Saving…" disabled={!isDirty}>
            Save changes
          </SubmitButton>
        </div>
      ) : null}
    </form>
  );
}
