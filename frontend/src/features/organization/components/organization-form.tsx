"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { toast } from "sonner";
import { FieldShell, TextField } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { applyApiError } from "@/lib/forms/api-errors";
import { updateOrganization } from "../api";
import {
  organizationFormSchema,
  toFormValues,
  toUpdateRequest,
  type OrganizationFormValues,
} from "../schemas";
import { buildTimeZoneOptions } from "../time-zones";
import type { Organization } from "../types";

const FIELDS = [
  "name",
  "timeZone",
  "expiringWindowDays",
  "reminderOffsets",
  "remindersEnabled",
] as const;

export function OrganizationForm({
  organization,
  canEdit,
}: {
  organization: Organization;
  canEdit: boolean;
}) {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const zones = useMemo(
    () => buildTimeZoneOptions(organization.timeZone),
    [organization.timeZone],
  );
  const {
    register,
    control,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<OrganizationFormValues>({
    resolver: zodResolver(organizationFormSchema),
    defaultValues: toFormValues(organization),
  });

  const watchedTimeZone = useWatch({ control, name: "timeZone" });

  async function onSubmit(values: OrganizationFormValues) {
    setFormError(null);
    try {
      const updated = await updateOrganization(toUpdateRequest(values));
      reset(toFormValues(updated));
      toast.success("Organization settings saved.");
      router.refresh(); // the shell shows the organization name
    } catch (error) {
      setFormError(
        applyApiError<OrganizationFormValues>(error, setError, {
          fields: FIELDS,
          // The server says `reminderOffsetsDays`; our text field is `reminderOffsets`.
          fieldAliases: { reminderOffsetsDays: "reminderOffsets" },
          statusMessages: {
            403: "You do not have permission to change organization settings.",
          },
        }),
      );
    }
  }

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      className="grid max-w-xl gap-5"
      aria-describedby={canEdit ? undefined : "org-readonly-note"}
    >
      {canEdit ? null : (
        <p
          id="org-readonly-note"
          className="rounded-md border bg-muted px-3 py-2 text-sm text-muted-foreground"
        >
          Only owners and admins can change organization settings.
        </p>
      )}
      <FormAlert message={formError} />

      <TextField
        id="name"
        label="Organization name"
        disabled={!canEdit}
        error={errors.name?.message}
        {...register("name")}
      />

      <FieldShell
        id="timeZone"
        label="Time zone"
        error={errors.timeZone?.message}
        help="Used for expiration dates and when reminders are sent."
      >
        {(aria) => (
          <Controller
            control={control}
            name="timeZone"
            render={({ field }) => (
              <Select
                value={field.value}
                onValueChange={field.onChange}
                disabled={!canEdit}
              >
                <SelectTrigger
                  {...aria}
                  className="w-full"
                  onBlur={field.onBlur}
                >
                  <SelectValue placeholder="Choose a time zone" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectLabel>United States</SelectLabel>
                    {zones.us.map((zone) => (
                      <SelectItem key={zone.value} value={zone.value}>
                        {zone.label}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                  {zones.other.length > 0 ? (
                    <SelectGroup>
                      <SelectLabel>All time zones</SelectLabel>
                      {zones.other.map((zone) => (
                        <SelectItem key={zone} value={zone}>
                          {zone}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  ) : null}
                </SelectContent>
              </Select>
            )}
          />
        )}
      </FieldShell>

      <TextField
        id="expiringWindowDays"
        label="Expiring window (days)"
        inputMode="numeric"
        disabled={!canEdit}
        help="Documents expiring within this many days are flagged as Expiring soon (1 to 180)."
        error={errors.expiringWindowDays?.message}
        {...register("expiringWindowDays")}
      />

      <fieldset className="grid gap-5 rounded-lg border p-4">
        <legend className="px-1 text-sm font-semibold">
          Expiration reminders
        </legend>
        <p id="reminders-help" className="text-sm text-muted-foreground">
          A daily email at about 7:00 {watchedTimeZone} to owners and admins
          when documents expire or cross these thresholds. Nothing is sent when
          there is nothing new.
        </p>
        <TextField
          id="reminderOffsets"
          label="Reminder days before expiry"
          disabled={!canEdit}
          help="Up to 5 different values from 1 to 180, separated by commas. Example: 30, 14, 7. A document is included in the digest once for each threshold it crosses."
          error={errors.reminderOffsets?.message}
          {...register("reminderOffsets")}
        />

        <div className="flex items-center gap-2">
          <Controller
            control={control}
            name="remindersEnabled"
            render={({ field }) => (
              <Checkbox
                id="remindersEnabled"
                checked={field.value}
                onCheckedChange={(checked) => field.onChange(checked === true)}
                disabled={!canEdit}
              />
            )}
          />
          <Label htmlFor="remindersEnabled">Send expiration reminders</Label>
        </div>
      </fieldset>

      {canEdit ? (
        <div>
          <SubmitButton
            pending={isSubmitting}
            pendingLabel="Saving…"
            disabled={!isDirty}
          >
            Save changes
          </SubmitButton>
        </div>
      ) : null}
    </form>
  );
}
