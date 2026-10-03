import { CheckCircle2, MinusCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { VendorStatus } from "../types";

// Icon + text + color, never color alone. This is the vendor lifecycle status, not compliance status.
export function VendorStatusBadge({ status }: { status: VendorStatus }) {
  const active = status === "ACTIVE";
  const Icon = active ? CheckCircle2 : MinusCircle;
  return (
    <Badge
      variant="outline"
      className={cn(
        "gap-1 font-medium",
        active
          ? "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200"
          : "border-border bg-muted text-muted-foreground",
      )}
    >
      <Icon aria-hidden="true" />
      <span>{active ? "Active" : "Inactive"}</span>
    </Badge>
  );
}
