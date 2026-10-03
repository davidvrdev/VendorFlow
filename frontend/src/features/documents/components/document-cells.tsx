import { FileText, FileX } from "lucide-react";
import { TableCell } from "@/components/ui/table";
import { formatCalendarDate, formatDate, formatFileSize, truncateFilename } from "@/lib/format";
import type { DocumentSummary } from "../types";
import { ReviewStatusBadge } from "./review-status";

/**
 * The document-dependent cells shared by the "Required documents" and "Other documents" tables:
 * current document · issue date · expiration date · review. (The compliance status column lives in the
 * requirements table; it is computed by the backend, never here.)
 */
export function DocumentCells({ document }: { document: DocumentSummary | null }) {
  if (!document) {
    return (
      <>
        <TableCell>
          <span className="inline-flex items-center gap-1.5 text-sm font-medium text-red-800 dark:text-red-200">
            <FileX className="size-4" aria-hidden="true" />
            No document
          </span>
        </TableCell>
        <TableCell className="text-muted-foreground">
          <span aria-label="No issue date">—</span>
        </TableCell>
        <TableCell className="text-muted-foreground">
          <span aria-label="No expiration date">—</span>
        </TableCell>
        <TableCell className="text-muted-foreground">
          <span aria-label="Not reviewed">—</span>
        </TableCell>
      </>
    );
  }
  return (
    <>
      <TableCell>
        <div className="flex items-start gap-2">
          <FileText className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <div className="min-w-0">
            <p className="text-sm font-medium break-all" title={document.originalFilename}>
              {truncateFilename(document.originalFilename)}
            </p>
            <p className="text-xs text-muted-foreground">
              Uploaded {formatDate(document.uploadedAt)} · {formatFileSize(document.sizeBytes)}
            </p>
          </div>
        </div>
      </TableCell>
      <TableCell className="whitespace-nowrap">{formatCalendarDate(document.issueDate) || <span className="text-muted-foreground">—</span>}</TableCell>
      <TableCell className="whitespace-nowrap">
        {formatCalendarDate(document.expirationDate) || <span className="text-muted-foreground">—</span>}
      </TableCell>
      <TableCell>
        <ReviewStatusBadge document={document} />
      </TableCell>
    </>
  );
}
