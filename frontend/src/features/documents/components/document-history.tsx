"use client";

import { Download } from "lucide-react";
import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCalendarDate, formatDate, formatFileSize, truncateFilename } from "@/lib/format";
import { errorMessage } from "@/lib/forms/api-errors";
import { downloadHref, listVendorDocuments } from "../api";
import type { DocumentState, DocumentSummary } from "../types";
import { PortalSourceBadge } from "./document-cells";
import { ReviewStatusBadge } from "./review-status";

const STATE_LABELS: Record<DocumentState, string> = { CURRENT: "Current", CANDIDATE: "Pending review", SUPERSEDED: "Superseded", ARCHIVED: "Archived" };

type Loaded = { version: string; documents: DocumentSummary[] } | { version: string; error: string };

interface DocumentHistoryProps {
  vendorId: string;
  /** Changes whenever the vendor's documents change (ids of current documents), so an open list reloads. */
  version: string;
}

/** Superseded and archived documents of a vendor, loaded on demand (GET ...?includeHistory=true). */
export function DocumentHistory({ vendorId, version }: DocumentHistoryProps) {
  const [open, setOpen] = useState(false);
  const [result, setResult] = useState<Loaded | null>(null);
  const [attempt, setAttempt] = useState(0);
  // Loading is derived (open and nothing loaded for this version yet), so effects never set state synchronously.
  const loading = open && result?.version !== version;

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    listVendorDocuments(vendorId, true)
      .then((documents) => !cancelled && setResult({ version, documents }))
      .catch((error: unknown) => !cancelled && setResult({ version, error: errorMessage(error) }));
    return () => {
      cancelled = true;
    };
  }, [open, vendorId, version, attempt]);

  function retry() {
    setResult(null);
    setAttempt((value) => value + 1);
  }

  const documents = result && "documents" in result ? result.documents.filter((document) => document.state !== "CURRENT") : [];
  const error = result && "error" in result ? result.error : null;

  return (
    <Card id="document-history">
      <CardHeader>
        <CardTitle>
          <h2>Document history</h2>
        </CardTitle>
      </CardHeader>
      <CardContent className="grid gap-4">
        <div>
          <Button variant="outline" size="sm" aria-expanded={open} aria-controls="document-history-list" onClick={() => setOpen((value) => !value)}>
            {open ? "Hide document history" : "Show document history"}
          </Button>
        </div>
        <div id="document-history-list" aria-live="polite">
          {!open ? null : loading ? (
            <p role="status" className="text-sm text-muted-foreground">
              Loading document history…
            </p>
          ) : error ? (
            <p role="alert" className="text-sm text-destructive">
              {error}{" "}
              <button type="button" className="underline" onClick={retry}>
                Try again
              </button>
            </p>
          ) : documents.length === 0 ? (
            <p className="text-sm text-muted-foreground">No replaced or archived documents yet.</p>
          ) : (
            <ul className="divide-y rounded-lg border">
              {documents.map((document) => (
                <li key={document.id} className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
                  <div className="grid min-w-0 gap-1">
                    <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                      {document.documentType.name}
                      <Badge variant="secondary">{STATE_LABELS[document.state]}</Badge>
                      {document.source === "PORTAL" ? <PortalSourceBadge /> : null}
                    </p>
                    <p className="text-sm break-all" title={document.originalFilename}>
                      {truncateFilename(document.originalFilename, 48)} <span className="text-muted-foreground">({formatFileSize(document.sizeBytes)})</span>
                    </p>
                    <p className="text-xs text-muted-foreground">
                      Uploaded {formatDate(document.uploadedAt)}
                      {document.uploadedBy ? ` by ${document.uploadedBy.fullName}` : ""}
                      {document.expirationDate ? ` · Expired or expires ${formatCalendarDate(document.expirationDate)}` : ""}
                    </p>
                    <ReviewStatusBadge document={document} />
                  </div>
                  <a
                    href={downloadHref(document.id)}
                    className="inline-flex items-center gap-1.5 text-sm font-medium underline-offset-4 hover:underline"
                    aria-label={`Download ${document.originalFilename} (${STATE_LABELS[document.state].toLowerCase()})`}
                  >
                    <Download className="size-4" aria-hidden="true" />
                    Download
                  </a>
                </li>
              ))}
            </ul>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
