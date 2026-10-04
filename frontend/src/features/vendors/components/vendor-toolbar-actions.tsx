import { Download, Plus, Upload } from "lucide-react";
import Link from "next/link";
import { Button, buttonVariants } from "@/components/ui/button";
import { exportTooLarge, exportUrl } from "../export";
import type { VendorListState } from "../list-state";

interface VendorToolbarActionsProps {
  state: VendorListState;
  totalItems: number;
  canCreate: boolean;
  canImport: boolean;
}

/** Right-hand toolbar buttons. Visibility mirrors permissions cosmetically; the backend enforces them. */
export function VendorToolbarActions({ state, totalItems, canCreate, canImport }: VendorToolbarActionsProps) {
  const tooLarge = exportTooLarge(totalItems);
  return (
    <div className="ml-auto flex flex-wrap items-start gap-2">
      <div className="grid gap-1">
        {tooLarge ? (
          <>
            <Button variant="outline" disabled aria-describedby="export-limit-note">
              <Download aria-hidden="true" data-icon="inline-start" />
              Export CSV
            </Button>
            <p id="export-limit-note" className="max-w-56 text-xs text-muted-foreground">
              Too many vendors to export at once. Narrow the filters to 10,000 or fewer.
            </p>
          </>
        ) : (
          // Plain navigation download of the filtered list; the server sets Content-Disposition.
          <a href={exportUrl(state)} download className={buttonVariants({ variant: "outline" })}>
            <Download aria-hidden="true" data-icon="inline-start" />
            Export CSV
          </a>
        )}
      </div>
      {canImport ? (
        <Link href="/vendors/import" className={buttonVariants({ variant: "outline" })}>
          <Upload aria-hidden="true" data-icon="inline-start" />
          Import CSV
        </Link>
      ) : null}
      {canCreate ? (
        <Link href="/vendors/new" className={buttonVariants()}>
          <Plus aria-hidden="true" data-icon="inline-start" />
          Add vendor
        </Link>
      ) : null}
    </div>
  );
}
