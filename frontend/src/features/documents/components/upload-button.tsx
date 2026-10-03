"use client";

import { Upload } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import type { DocumentSummary, DocumentType } from "../types";
import { UploadDialog } from "./upload-dialog";

interface UploadButtonProps {
  vendorId: string;
  companyName: string;
  documentTypes: DocumentType[];
  currentByType: Record<string, DocumentSummary>;
}

/** General "Upload document" entry point: the type is chosen freely from the active types. */
export function UploadButton({ vendorId, companyName, documentTypes, currentByType }: UploadButtonProps) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
        <Upload aria-hidden="true" data-icon="inline-start" />
        Upload document
      </Button>
      <UploadDialog
        open={open}
        onOpenChange={setOpen}
        vendorId={vendorId}
        companyName={companyName}
        documentTypes={documentTypes}
        currentByType={currentByType}
      />
    </>
  );
}
