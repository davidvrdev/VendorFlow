"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { FieldShell, TextField } from "@/components/forms/field";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { buttonVariants } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ApiError } from "@/lib/api/errors";
import { applyApiError } from "@/lib/forms/api-errors";
import { createVendor, updateVendor } from "../api";
import {
  EMPTY_VENDOR_FORM,
  toVendorInput,
  vendorFormSchema,
  vendorToFormValues,
  type VendorFormOutput,
  type VendorFormValues,
} from "../schemas";
import type { VendorDetail } from "../types";

const FIELDS = ["companyName", "contactName", "email", "phone", "category", "notes"] as const;
export const DUPLICATE_VENDOR_MESSAGE = "A vendor with this name already exists.";

interface VendorFormProps {
  /** Present => edit mode (PUT), absent => create mode (POST). */
  vendor?: VendorDetail;
  /** Existing categories, offered as suggestions (free text is still allowed). */
  categories?: string[];
}

export function VendorForm({ vendor, categories = [] }: VendorFormProps) {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<VendorFormValues, unknown, VendorFormOutput>({
    resolver: zodResolver(vendorFormSchema),
    defaultValues: vendor ? vendorToFormValues(vendor) : EMPTY_VENDOR_FORM,
  });

  async function onSubmit(values: VendorFormOutput) {
    setFormError(null);
    try {
      const input = toVendorInput(values);
      const saved = vendor ? await updateVendor(vendor.id, input) : await createVendor(input);
      toast.success(vendor ? "Vendor updated." : "Vendor added.");
      router.push(`/vendors/${encodeURIComponent(saved.id)}`);
      router.refresh();
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setError("companyName", { type: "server", message: DUPLICATE_VENDOR_MESSAGE }, { shouldFocus: true });
        return;
      }
      setFormError(
        applyApiError<VendorFormValues>(error, setError, {
          fields: FIELDS,
          statusMessages: { 403: "You do not have permission to change vendors." },
        }),
      );
    }
  }

  const cancelHref = vendor ? `/vendors/${encodeURIComponent(vendor.id)}` : "/vendors";

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="grid max-w-xl gap-5">
      <FormAlert message={formError} />

      <TextField
        id="companyName"
        label="Company name"
        autoComplete="organization"
        required
        aria-required="true"
        error={errors.companyName?.message}
        {...register("companyName")}
      />
      <TextField id="contactName" label="Contact name" error={errors.contactName?.message} {...register("contactName")} />
      <TextField id="email" label="Email" type="email" autoComplete="off" error={errors.email?.message} {...register("email")} />
      <TextField
        id="phone"
        label="Phone"
        type="tel"
        autoComplete="off"
        help="Digits, spaces and + ( ) . - x ext."
        error={errors.phone?.message}
        {...register("phone")}
      />
      <TextField
        id="category"
        label="Category"
        list="vendor-categories"
        autoComplete="off"
        help="For example Plumbing or Landscaping. Used to filter the vendor list."
        error={errors.category?.message}
        {...register("category")}
      />
      {categories.length > 0 ? (
        <datalist id="vendor-categories">
          {categories.map((category) => (
            <option key={category} value={category} />
          ))}
        </datalist>
      ) : null}

      <FieldShell id="notes" label="Notes" error={errors.notes?.message}>
        {(aria) => <Textarea {...aria} rows={4} {...register("notes")} />}
      </FieldShell>

      <div className="flex flex-wrap gap-3">
        <SubmitButton pending={isSubmitting} pendingLabel="Saving…">
          {vendor ? "Save changes" : "Add vendor"}
        </SubmitButton>
        <Link href={cancelHref} className={buttonVariants({ variant: "outline" })}>
          Cancel
        </Link>
      </div>
    </form>
  );
}
