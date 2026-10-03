import { AlertTriangle, CheckCircle2, Clock, Store, type LucideIcon } from "lucide-react";
import Link from "next/link";
import { cn } from "@/lib/utils";
import type { DashboardSummary } from "../types";
import { documentsLine, summaryTiles, type TileSpec } from "../view";

// Same icon + text + color language as the vendor compliance badges.
const TONE: Record<TileSpec["key"], { Icon: LucideIcon; className: string }> = {
  active: { Icon: Store, className: "text-foreground" },
  COMPLIANT: { Icon: CheckCircle2, className: "text-emerald-800 dark:text-emerald-200" },
  ATTENTION: { Icon: Clock, className: "text-amber-900 dark:text-amber-200" },
  NON_COMPLIANT: { Icon: AlertTriangle, className: "text-red-900 dark:text-red-100" },
};

export function SummaryTiles({ summary }: { summary: DashboardSummary }) {
  const line = documentsLine(summary);
  return (
    <section aria-labelledby="summary-heading" className="mb-8">
      <h2 id="summary-heading" className="sr-only">
        Summary
      </h2>
      <ul className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {summaryTiles(summary).map((tile) => {
          const { Icon, className } = TONE[tile.key];
          return (
            <li key={tile.key}>
              <Link
                href={tile.href}
                className="block rounded-lg border bg-card p-4 transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <span className={cn("flex items-center gap-1.5 text-sm font-medium", className)}>
                  <Icon className="size-4" aria-hidden="true" />
                  {tile.label}
                </span>
                <span className="mt-2 block text-3xl font-semibold tabular-nums">{tile.value}</span>
              </Link>
            </li>
          );
        })}
      </ul>
      <p className="mt-3 text-sm text-muted-foreground">
        {line ? <span data-testid="documents-line">Documents: {line}</span> : <span>No document needs action.</span>}
        <span className="ml-3 text-xs">As of {summary.today}</span>
      </p>
    </section>
  );
}
