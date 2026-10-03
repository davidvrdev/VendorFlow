"use client";

import { Pencil } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Button, buttonVariants } from "@/components/ui/button";
import { can } from "@/features/organization/permissions";
import type { Role } from "@/features/organization/types";
import { errorMessage } from "@/lib/forms/api-errors";
import { deactivateVendor, reactivateVendor } from "../api";
import type { VendorStatus } from "../types";

interface VendorActionsProps {
  vendor: { id: string; companyName: string; status: VendorStatus };
  role: Role;
}

/** Header actions. Permissions only decide what is shown; the backend enforces them. */
export function VendorActions({ vendor, role }: VendorActionsProps) {
  const router = useRouter();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const canEdit = can(role, "VENDORS_WRITE");
  const canArchive = can(role, "ARCHIVE_AND_IMPORT");
  const deactivating = vendor.status === "ACTIVE";

  async function toggle() {
    await (deactivating ? deactivateVendor(vendor.id) : reactivateVendor(vendor.id));
    toast.success(deactivating ? `${vendor.companyName} was deactivated.` : `${vendor.companyName} was reactivated.`);
    router.refresh();
  }

  if (!canEdit && !canArchive) return null;

  return (
    <>
      {canEdit ? (
        <Link href={`/vendors/${encodeURIComponent(vendor.id)}/edit`} className={buttonVariants({ variant: "outline" })}>
          <Pencil aria-hidden="true" data-icon="inline-start" />
          Edit
        </Link>
      ) : null}
      {canArchive ? (
        <>
          <Button variant="outline" onClick={() => setConfirmOpen(true)}>
            {deactivating ? "Deactivate" : "Reactivate"}
          </Button>
          <ConfirmDialog
            open={confirmOpen}
            onOpenChange={setConfirmOpen}
            title={deactivating ? `Deactivate ${vendor.companyName}?` : `Reactivate ${vendor.companyName}?`}
            description={
              deactivating
                ? "The vendor will be hidden from the default vendor list and no longer need attention. Its documents and history are kept, and you can reactivate it at any time."
                : "The vendor will appear in the default vendor list again."
            }
            confirmLabel={deactivating ? "Deactivate vendor" : "Reactivate vendor"}
            pendingLabel={deactivating ? "Deactivating…" : "Reactivating…"}
            confirmVariant={deactivating ? "destructive" : "default"}
            onConfirm={toggle}
            describeError={(error) => errorMessage(error, { 403: "You do not have permission to do that." })}
          />
        </>
      ) : null}
    </>
  );
}
