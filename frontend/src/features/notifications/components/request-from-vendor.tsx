"use client";

import { Send } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Button } from "@/components/ui/button";
import type { Role } from "@/features/organization/types";
import { errorMessage } from "@/lib/forms/api-errors";
import { requestDocumentFromVendor } from "../api";
import { NO_EMAIL_HELP, REQUEST_ERROR_MESSAGES, requestAvailability, requestSentMessage } from "../request";

interface RequestFromVendorProps {
  vendorId: string;
  vendorName: string;
  documentTypeId: string;
  documentTypeName: string;
  /** Requirement/attention status; only MISSING, EXPIRED and EXPIRING offer the action. */
  status: string | undefined;
  role: Role | null | undefined;
  /** null/empty: the vendor has no email. undefined: not known in this view (dashboard). */
  vendorEmail: string | null | undefined;
}

/** "Request from vendor": confirmation dialog, POST, toast. Renders nothing when the action does not apply. */
export function RequestFromVendor({ vendorId, vendorName, documentTypeId, documentTypeName, status, role, vendorEmail }: RequestFromVendorProps) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const availability = requestAvailability({ status, role, vendorEmail });
  if (availability === "hidden") return null;

  const label = `Request from vendor: ${documentTypeName}`;
  if (availability === "no-email") {
    const helpId = `no-email-${vendorId}-${documentTypeId}`;
    return (
      <div className="flex flex-col items-end gap-1">
        <Button size="sm" variant="outline" disabled aria-label={label} aria-describedby={helpId}>
          <Send aria-hidden="true" data-icon="inline-start" /> Request from vendor
        </Button>
        <p id={helpId} className="max-w-48 text-right text-xs text-muted-foreground">
          {NO_EMAIL_HELP}.{" "}
          <Link href={`/vendors/${encodeURIComponent(vendorId)}/edit`} className="underline underline-offset-2">
            Edit vendor
          </Link>
        </p>
      </div>
    );
  }

  async function send() {
    const result = await requestDocumentFromVendor(vendorId, documentTypeId);
    toast.success(requestSentMessage(result.recipientEmail));
    router.refresh(); // the vendor history gains a "requested" event
  }

  return (
    <>
      <Button size="sm" variant="outline" aria-label={label} onClick={() => setOpen(true)}>
        <Send aria-hidden="true" data-icon="inline-start" /> Request from vendor
      </Button>
      <ConfirmDialog
        open={open}
        onOpenChange={setOpen}
        title={`Request ${documentTypeName} from ${vendorName}?`}
        description={
          vendorEmail ? (
            <>
              We will email <strong>{vendorEmail}</strong> asking for the {documentTypeName}. You can send one request per document per day.
            </>
          ) : (
            <>We will email the vendor&apos;s contact address asking for the {documentTypeName}. You can send one request per document per day.</>
          )
        }
        confirmLabel="Send request"
        pendingLabel="Sending…"
        confirmVariant="default"
        onConfirm={send}
        describeError={(error) => errorMessage(error, REQUEST_ERROR_MESSAGES)}
      />
    </>
  );
}
