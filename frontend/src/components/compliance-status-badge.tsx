import { AlertTriangle, CheckCircle2, Clock, Eye, FileX, type LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { ComplianceStatus } from "@/features/compliance/types";
import { cn } from "@/lib/utils";

interface StatusConfig {
  label: string;
  Icon: LucideIcon;
  className: string;
}

// Status is always icon + text + color: color alone never carries meaning (accessibility).
const STATUS_CONFIG: Record<ComplianceStatus, StatusConfig> = {
  MISSING: {
    label: "Missing",
    Icon: FileX,
    className: "border-red-200 bg-red-50 text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-200",
  },
  OK: {
    label: "OK",
    Icon: CheckCircle2,
    className:
      "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  },
  EXPIRING: {
    label: "Expiring soon",
    Icon: Clock,
    className:
      "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200",
  },
  EXPIRED: {
    label: "Expired",
    Icon: AlertTriangle,
    className: "border-red-300 bg-red-100 text-red-900 dark:border-red-800 dark:bg-red-950 dark:text-red-100",
  },
  REVIEW_REQUIRED: {
    label: "Needs review",
    Icon: Eye,
    className: "border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-900 dark:bg-sky-950 dark:text-sky-200",
  },
};

export function ComplianceStatusBadge({ status, className, label: labelOverride }: { status: ComplianceStatus; className?: string; label?: string }) {
  const { label: defaultLabel, Icon, className: tone } = STATUS_CONFIG[status];
  const label = labelOverride ?? defaultLabel;
  return (
    <Badge variant="outline" className={cn("gap-1 font-medium", tone, className)}>
      <Icon aria-hidden="true" />
      <span>{label}</span>
    </Badge>
  );
}
