import { CheckCircle2, Clock, XCircle, type LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { DocumentSummary, ReviewStatus } from "../types";

const CONFIG: Record<ReviewStatus, { label: string; Icon: LucideIcon; className: string }> = {
  PENDING: {
    label: "Pending review",
    Icon: Clock,
    className: "border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-900 dark:bg-sky-950 dark:text-sky-200",
  },
  APPROVED: {
    label: "Approved",
    Icon: CheckCircle2,
    className: "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  },
  REJECTED: {
    label: "Rejected",
    Icon: XCircle,
    className: "border-red-200 bg-red-50 text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-200",
  },
};

/** Review state as icon + text + color. A rejection note is shown as plain text under the badge (no hover needed). */
export function ReviewStatusBadge({ document }: { document: Pick<DocumentSummary, "reviewStatus" | "reviewNote"> }) {
  const { label, Icon, className } = CONFIG[document.reviewStatus];
  return (
    <div className="grid gap-1">
      <Badge variant="outline" className={cn(className)}>
        <Icon aria-hidden="true" />
        {label}
      </Badge>
      {document.reviewStatus === "REJECTED" && document.reviewNote ? (
        <p className="max-w-56 break-words text-xs text-muted-foreground">Note: {document.reviewNote}</p>
      ) : null}
    </div>
  );
}
