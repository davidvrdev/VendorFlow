"use client";

import { Clock } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { FormAlert } from "@/components/forms/form-alert";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/lib/format";
import { canCommit, type PreviewState, type RowFilter } from "../import-state";
import { confirmQuestion, filterCount, filterRows, FILTERS } from "../view";
import { PreviewTable } from "./preview-table";

interface PreviewStepProps {
  state: PreviewState;
  onFilter: (filter: RowFilter) => void;
  /** Resolves on success; throws to keep the dialog open with the mapped message. */
  onCommit: () => Promise<void>;
  describeError: (error: unknown) => string;
  onReset: () => void;
}

export function PreviewStep({ state, onFilter, onCommit, describeError, onReset }: PreviewStepProps) {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const { preview, filter } = state;
  const { summary } = preview;
  const hasErrors = summary.error > 0;
  const nothingToDo = !hasErrors && summary.create + summary.update === 0;
  const rows = filterRows(preview.rows, filter);

  return (
    <div className="grid gap-5">
      <ul className="flex flex-wrap gap-2 text-sm" aria-label="Preview summary">
        <Chip>{summary.create} to create</Chip>
        <Chip>{summary.update} to update</Chip>
        <Chip>{summary.unchanged} unchanged</Chip>
        <Chip tone={hasErrors ? "error" : "neutral"}>{summary.error} with errors</Chip>
      </ul>
      <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
        <Clock className="size-4" aria-hidden="true" />
        This preview expires at {formatDateTime(preview.expiresAt)}. Nothing has been imported yet.
      </p>

      <FormAlert message={state.error} />
      {hasErrors ? (
        <p role="status" className="text-sm font-medium text-red-800 dark:text-red-300">
          Fix the errors in your file and upload it again.
        </p>
      ) : null}
      {nothingToDo ? (
        <p role="status" className="text-sm text-muted-foreground">
          There is nothing to import: every row matches an existing vendor with no differences.
        </p>
      ) : null}

      <div role="group" aria-label="Filter rows" className="flex flex-wrap gap-2">
        {FILTERS.map(({ value, label }) => (
          <Button
            key={value}
            type="button"
            size="sm"
            variant={filter === value ? "default" : "outline"}
            aria-pressed={filter === value}
            onClick={() => onFilter(value)}
          >
            {label} ({filterCount(summary, value)})
          </Button>
        ))}
      </div>

      <PreviewTable rows={rows} />

      <div className="flex flex-wrap gap-3">
        <Button type="button" disabled={!canCommit(state)} aria-busy={state.committing} onClick={() => setConfirmOpen(true)}>
          Import vendors
        </Button>
        <Button type="button" variant="outline" disabled={state.committing} onClick={onReset}>
          Upload a different file
        </Button>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={confirmQuestion(summary)}
        description="Existing vendors keep any value your file leaves empty. This changes your vendor list immediately."
        confirmLabel="Import vendors"
        pendingLabel="Importing…"
        confirmVariant="default"
        onConfirm={onCommit}
        describeError={describeError}
      />
    </div>
  );
}

function Chip({ children, tone = "neutral" }: { children: React.ReactNode; tone?: "neutral" | "error" }) {
  return (
    <li
      className={
        tone === "error"
          ? "rounded-full border border-red-200 bg-red-50 px-3 py-1 font-medium text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-200"
          : "rounded-full border bg-muted px-3 py-1 font-medium"
      }
    >
      {children}
    </li>
  );
}
