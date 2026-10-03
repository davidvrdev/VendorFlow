import { AlertTriangle, CheckCircle2, CircleSlash, Clock, type LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { VendorCompliance } from "@/features/compliance/types";
import { cn } from "@/lib/utils";

interface Config {
  label: string;
  Icon: LucideIcon;
  className: string;
}

// Icon + text + color; color never carries meaning alone.
const CONFIG: Record<VendorCompliance, Config> = {
  COMPLIANT: {
    label: "Compliant",
    Icon: CheckCircle2,
    className: "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  },
  ATTENTION: {
    label: "Needs attention",
    Icon: Clock,
    className: "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200",
  },
  NON_COMPLIANT: {
    label: "Non-compliant",
    Icon: AlertTriangle,
    className: "border-red-300 bg-red-100 text-red-900 dark:border-red-800 dark:bg-red-950 dark:text-red-100",
  },
};

interface Props {
  status: VendorCompliance;
  /** Active requirements. With 0 the vendor is trivially compliant, so we say that nothing is required. */
  requirementCount: number;
  className?: string;
}

export function VendorComplianceBadge({ status, requirementCount, className }: Props) {
  if (requirementCount === 0) {
    return (
      <Badge variant="outline" className={cn("gap-1 font-medium text-muted-foreground", className)}>
        <CircleSlash aria-hidden="true" />
        <span>No requirements set</span>
      </Badge>
    );
  }
  const { label, Icon, className: tone } = CONFIG[status];
  return (
    <Badge variant="outline" className={cn("gap-1 font-medium", tone, className)}>
      <Icon aria-hidden="true" />
      <span>{label}</span>
    </Badge>
  );
}
