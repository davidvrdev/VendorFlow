"use client";

import { CheckCircle2, CalendarDays, Download, MoreHorizontal, Archive, Upload, XCircle } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { Role } from "@/features/organization/types";
import { errorMessage } from "@/lib/forms/api-errors";
import { archiveDocument, downloadHref, reviewDocument } from "../api";
import { hasAnyAction, rowActions } from "../actions";
import type { DocumentSummary, DocumentType } from "../types";
import { EditDatesDialog } from "./edit-dates-dialog";
import { RejectDialog } from "./reject-dialog";
import { UploadDialog } from "./upload-dialog";

interface DocumentRowMenuProps {
  vendorId: string;
  companyName: string;
  role: Role;
  /** Name of the row's document type, used in the accessible label. */
  typeName: string;
  typeId: string;
  /** The row's CURRENT document, or null when the requirement has none. */
  document: DocumentSummary | null;
  /** False for requirements whose type was deactivated: no uploads, everything else still works. */
  typeActive?: boolean;
  /** Active types, for the upload dialog's picker. */
  documentTypes: DocumentType[];
  currentByType: Record<string, DocumentSummary>;
}

type Dialogs = "upload" | "reject" | "dates" | "archive" | null;

/** One compact, keyboard-usable menu per row. Items depend on role (cosmetic) and document state. */
export function DocumentRowMenu({
  vendorId,
  companyName,
  role,
  typeName,
  typeId,
  document,
  typeActive = true,
  documentTypes,
  currentByType,
}: DocumentRowMenuProps) {
  const router = useRouter();
  const [dialog, setDialog] = useState<Dialogs>(null);
  const [approving, setApproving] = useState(false);
  const actions = rowActions(role, document, typeActive);
  if (!hasAnyAction(actions)) return null;

  async function approve() {
    if (!document) return;
    setApproving(true);
    try {
      await reviewDocument(document.id, "APPROVED");
      toast.success("Document approved.");
      router.refresh();
    } catch (error) {
      toast.error(errorMessage(error, { 403: "You do not have permission to review documents.", 409: "This document is no longer current." }));
    } finally {
      setApproving(false);
    }
  }

  async function archive() {
    if (!document) return;
    await archiveDocument(document.id);
    toast.success("Document archived.");
    router.refresh();
  }

  const showReview = actions.approve || actions.reject;
  const showUpload = actions.upload || actions.replace;

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon-sm" aria-label={`Actions for ${typeName}`} disabled={approving}>
            <MoreHorizontal aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          {showUpload ? (
            <DropdownMenuItem onSelect={() => setDialog("upload")}>
              <Upload aria-hidden="true" />
              {actions.replace ? "Replace" : "Upload"}
            </DropdownMenuItem>
          ) : null}
          {actions.download && document ? (
            <DropdownMenuItem asChild>
              {/* Plain same-origin link: the backend replies with Content-Disposition: attachment. */}
              <a href={downloadHref(document.id)}>
                <Download aria-hidden="true" />
                Download
              </a>
            </DropdownMenuItem>
          ) : null}
          {showReview ? <DropdownMenuSeparator /> : null}
          {actions.approve ? (
            <DropdownMenuItem onSelect={() => void approve()}>
              <CheckCircle2 aria-hidden="true" />
              Approve
            </DropdownMenuItem>
          ) : null}
          {actions.reject ? (
            <DropdownMenuItem onSelect={() => setDialog("reject")}>
              <XCircle aria-hidden="true" />
              Reject…
            </DropdownMenuItem>
          ) : null}
          {actions.editDates || actions.archive ? <DropdownMenuSeparator /> : null}
          {actions.editDates ? (
            <DropdownMenuItem onSelect={() => setDialog("dates")}>
              <CalendarDays aria-hidden="true" />
              Edit dates
            </DropdownMenuItem>
          ) : null}
          {actions.archive ? (
            <DropdownMenuItem variant="destructive" onSelect={() => setDialog("archive")}>
              <Archive aria-hidden="true" />
              Archive…
            </DropdownMenuItem>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>

      {showUpload ? (
        <UploadDialog
          open={dialog === "upload"}
          onOpenChange={(open) => setDialog(open ? "upload" : null)}
          vendorId={vendorId}
          companyName={companyName}
          documentTypes={documentTypes}
          initialTypeId={typeId}
          currentByType={currentByType}
        />
      ) : null}
      {actions.reject && document ? (
        <RejectDialog open={dialog === "reject"} onOpenChange={(open) => setDialog(open ? "reject" : null)} document={document} />
      ) : null}
      {actions.editDates && document ? (
        <EditDatesDialog open={dialog === "dates"} onOpenChange={(open) => setDialog(open ? "dates" : null)} document={document} />
      ) : null}
      {actions.archive && document ? (
        <ConfirmDialog
          open={dialog === "archive"}
          onOpenChange={(open) => setDialog(open ? "archive" : null)}
          title={`Archive ${typeName}?`}
          description="The document is archived and no longer counts as the current document for this requirement. It stays in the document history and can still be downloaded."
          confirmLabel="Archive document"
          pendingLabel="Archiving…"
          onConfirm={archive}
          describeError={(error) => errorMessage(error, { 403: "You do not have permission to archive documents." })}
        />
      ) : null}
    </>
  );
}
