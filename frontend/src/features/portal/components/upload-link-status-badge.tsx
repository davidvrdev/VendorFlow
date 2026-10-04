import { Ban, CheckCircle2, Clock, PackageCheck, type LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { UploadLinkStatus } from "../schemas";

const CONFIG: Record<UploadLinkStatus, { label: string; Icon: LucideIcon; className: string }> = {
  ACTIVE: {
    label: "Active",
    Icon: CheckCircle2,
    className: "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  },
  EXPIRED: {
    label: "Expired",
    Icon: Clock,
    className: "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200",
  },
  REVOKED: {
    label: "Revoked",
    Icon: Ban,
    className: "border-red-200 bg-red-50 text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-200",
  },
  EXHAUSTED: {
    label: "Limit reached",
    Icon: PackageCheck,
    className: "border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-900 dark:bg-sky-950 dark:text-sky-200",
  },
};

/** Link status as icon + text + color. */
export function UploadLinkStatusBadge({ status }: { status: UploadLinkStatus }) {
  const { label, Icon, className } = CONFIG[status];
  return (
    <Badge variant="outline" className={cn("gap-1 font-medium", className)}>
      <Icon aria-hidden="true" />
      {label}
    </Badge>
  );
}
