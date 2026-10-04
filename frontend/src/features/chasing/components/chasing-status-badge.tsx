import { AlertTriangle, CheckCircle2, MailX, PauseCircle, Send, UserX, type LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { VendorChasingState } from "../schemas";
import { chasingStatusLabel } from "../view";

const STYLES = {
  neutral: "border-border bg-muted text-muted-foreground",
  info: "border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-900 dark:bg-sky-950 dark:text-sky-200",
  warning: "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200",
  danger: "border-red-200 bg-red-50 text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-200",
  ok: "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
};

function look(state: Pick<VendorChasingState, "status" | "pausedReason">): { Icon: LucideIcon; className: string } {
  switch (state.status) {
    case "ACTIVE":
      return { Icon: Send, className: STYLES.info };
    case "EXHAUSTED":
      return { Icon: AlertTriangle, className: STYLES.warning };
    case "NO_EMAIL":
      return { Icon: MailX, className: STYLES.warning };
    case "PAUSED":
      return state.pausedReason === "OPT_OUT" ? { Icon: UserX, className: STYLES.danger } : { Icon: PauseCircle, className: STYLES.neutral };
    default:
      return { Icon: CheckCircle2, className: STYLES.ok };
  }
}

/** Chasing status as icon + text + color. */
export function ChasingStatusBadge({ state }: { state: Pick<VendorChasingState, "status" | "pausedReason"> }) {
  const { Icon, className } = look(state);
  return (
    <Badge variant="outline" className={cn("gap-1 font-medium", className)}>
      <Icon aria-hidden="true" />
      {chasingStatusLabel(state)}
    </Badge>
  );
}
