import { CircleAlert, Equal, Pencil, Plus } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { ACTION_LABELS } from "../view";
import type { ImportRowAction } from "../types";

const STYLES: Record<ImportRowAction, { icon: typeof Plus; className: string }> = {
  CREATE: {
    icon: Plus,
    className: "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  },
  UPDATE: {
    icon: Pencil,
    className: "border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-900 dark:bg-sky-950 dark:text-sky-200",
  },
  UNCHANGED: { icon: Equal, className: "border-border bg-muted text-muted-foreground" },
  ERROR: {
    icon: CircleAlert,
    className: "border-red-200 bg-red-50 text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-200",
  },
};

/** Icon + text + color, never color alone. */
export function ActionBadge({ action }: { action: ImportRowAction }) {
  const { icon: Icon, className } = STYLES[action];
  return (
    <Badge variant="outline" className={cn("gap-1 font-medium", className)}>
      <Icon aria-hidden="true" />
      <span>{ACTION_LABELS[action]}</span>
    </Badge>
  );
}
