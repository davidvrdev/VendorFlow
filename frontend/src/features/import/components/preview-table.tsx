import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { ImportPreviewRow } from "../types";
import { rowErrorsText } from "../view";
import { ActionBadge } from "./action-badge";

export function PreviewTable({ rows }: { rows: ImportPreviewRow[] }) {
  if (rows.length === 0) {
    return <p className="rounded-md border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">No rows in this view.</p>;
  }
  return (
    <div className="overflow-x-auto rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-20">Row</TableHead>
            <TableHead>Company name</TableHead>
            <TableHead className="w-32">Action</TableHead>
            <TableHead>Details</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.rowNumber}>
              <TableCell className="tabular-nums">{row.rowNumber}</TableCell>
              <TableCell className="font-medium">{row.companyName ?? <span className="text-muted-foreground">(empty)</span>}</TableCell>
              <TableCell>
                <ActionBadge action={row.action} />
              </TableCell>
              <TableCell className="text-sm">
                {row.action === "UPDATE" && row.changes.length > 0 ? (
                  <span>
                    <span className="text-muted-foreground">Changes: </span>
                    {row.changes.join(", ")}
                  </span>
                ) : null}
                {row.errors.length > 0 ? (
                  <ul className="list-disc space-y-0.5 pl-4 text-red-800 dark:text-red-300">
                    {rowErrorsText(row).map((text) => (
                      <li key={text}>{text}</li>
                    ))}
                  </ul>
                ) : null}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
