"use client";

import { CheckCircle2, XCircle } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { ComplianceStatusBadge } from "@/components/compliance-status-badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { expirationText } from "@/features/compliance/format";
import { reviewDocument } from "@/features/documents/api";
import { RejectDialog } from "@/features/documents/components/reject-dialog";
import { UploadDialog } from "@/features/documents/components/upload-dialog";
import type { DocumentType } from "@/features/documents/types";
import type { Role } from "@/features/organization/types";
import { errorMessage } from "@/lib/forms/api-errors";
import type { AttentionItem } from "../types";
import { actionAriaLabel, rowAction } from "../view";

interface AttentionListProps {
  items: AttentionItem[];
  role: Role | null | undefined;
  /** Active document types, for the upload dialog (empty for roles that cannot upload). */
  documentTypes: DocumentType[];
}

export function AttentionList({ items, role, documentTypes }: AttentionListProps) {
  return (
    <ul className="divide-y rounded-lg border bg-card">
      {items.map((item) => (
        <AttentionRow key={`${item.vendorId}:${item.documentTypeId}`} item={item} role={role} documentTypes={documentTypes} />
      ))}
    </ul>
  );
}

type Dialogs = "upload" | "review" | "reject" | null;

function AttentionRow({ item, role, documentTypes }: { item: AttentionItem; role: AttentionListProps["role"]; documentTypes: DocumentType[] }) {
  const [dialog, setDialog] = useState<Dialogs>(null);
  const action = rowAction(item, role);
  const expiry = expirationText(item.daysUntilExpiration);
  const label = actionAriaLabel(action, item);

  return (
    <li className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
      <div className="min-w-0 flex-1 basis-56">
        <Link href={`/vendors/${item.vendorId}`} className="font-medium hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
          {item.vendorName}
        </Link>
        <p className="text-sm text-muted-foreground">{item.documentTypeName}</p>
      </div>
      <div className="flex flex-col items-start gap-1 sm:w-44">
        <ComplianceStatusBadge status={item.status} />
        {expiry ? <span className="text-xs text-muted-foreground">{expiry}</span> : null}
      </div>
      {action.kind === "link" ? (
        <Button asChild variant="outline" size="sm">
          <Link href={`/vendors/${item.vendorId}`} aria-label={label}>
            {action.label}
          </Link>
        </Button>
      ) : (
        <Button
          size="sm"
          variant={action.kind === "review" ? "default" : "outline"}
          aria-label={label}
          onClick={() => setDialog(action.kind === "review" ? "review" : "upload")}
        >
          {action.label}
        </Button>
      )}

      {dialog === "upload" ? (
        <UploadDialog
          open
          onOpenChange={(open) => setDialog(open ? "upload" : null)}
          vendorId={item.vendorId}
          companyName={item.vendorName}
          documentTypes={documentTypes}
          initialTypeId={item.documentTypeId}
          currentByType={item.documentId ? { [item.documentTypeId]: { id: item.documentId } } : {}}
        />
      ) : null}
      {item.documentId && (dialog === "review" || dialog === "reject") ? (
        <>
          <ReviewDialog
            open={dialog === "review"}
            onOpenChange={(open) => setDialog(open ? "review" : null)}
            item={item}
            documentId={item.documentId}
            onReject={() => setDialog("reject")}
          />
          <RejectDialog
            open={dialog === "reject"}
            onOpenChange={(open) => setDialog(open ? "reject" : null)}
            document={{ id: item.documentId, documentType: { name: item.documentTypeName } }}
          />
        </>
      ) : null}
    </li>
  );
}

function ReviewDialog({
  open,
  onOpenChange,
  item,
  documentId,
  onReject,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  item: AttentionItem;
  documentId: string;
  onReject: () => void;
}) {
  const router = useRouter();
  const [pending, setPending] = useState(false);

  async function approve() {
    setPending(true);
    try {
      await reviewDocument(documentId, "APPROVED");
      toast.success("Document approved.");
      onOpenChange(false);
      router.refresh();
    } catch (error) {
      toast.error(errorMessage(error, { 403: "You do not have permission to review documents.", 409: "This document is no longer current." }));
    } finally {
      setPending(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => (pending ? undefined : onOpenChange(next))}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Review document</DialogTitle>
          <DialogDescription>
            {item.documentTypeName} for {item.vendorName}. Approve it, or reject it with a reason.
          </DialogDescription>
        </DialogHeader>
        <p className="text-sm">
          <Link href={`/vendors/${item.vendorId}`} className="underline">
            Open the vendor page
          </Link>{" "}
          to download and inspect the file first.
        </p>
        <DialogFooter>
          <Button variant="outline" disabled={pending} onClick={onReject}>
            <XCircle aria-hidden="true" data-icon="inline-start" /> Reject…
          </Button>
          <Button disabled={pending} onClick={() => void approve()}>
            <CheckCircle2 aria-hidden="true" data-icon="inline-start" /> {pending ? "Approving…" : "Approve"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
