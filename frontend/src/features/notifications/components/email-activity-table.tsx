import { AlertTriangle, CheckCircle2, Clock, RefreshCw, XCircle, type LucideIcon } from "lucide-react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { kindLabel, statusLabel, type StatusTone } from "../labels";
import type { NotificationView } from "../types";

const TONE: Record<StatusTone, { icon: LucideIcon; className: string }> = {
  success: { icon: CheckCircle2, className: "text-green-700 dark:text-green-400" },
  pending: { icon: Clock, className: "text-muted-foreground" },
  warning: { icon: RefreshCw, className: "text-amber-700 dark:text-amber-400" },
  danger: { icon: XCircle, className: "text-red-700 dark:text-red-400" },
};

export function EmailStatus({ status }: { status: NotificationView["status"] }) {
  const { text, tone } = statusLabel(status);
  const { icon: Icon, className } = TONE[tone];
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-sm font-medium", className)}>
      <Icon className="size-4" aria-hidden="true" />
      {text}
    </span>
  );
}

export function EmailActivityTable({ items }: { items: NotificationView[] }) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <caption className="sr-only">Emails sent by VendorFlow for this organization</caption>
        <TableHeader>
          <TableRow>
            <TableHead scope="col">Email</TableHead>
            <TableHead scope="col">Recipient</TableHead>
            <TableHead scope="col">Status</TableHead>
            <TableHead scope="col" className="text-right">
              Attempts
            </TableHead>
            <TableHead scope="col">Created</TableHead>
            <TableHead scope="col">Sent</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((item) => (
            <TableRow key={item.id}>
              <TableCell className="font-medium">{kindLabel(item.kind)}</TableCell>
              <TableCell className="break-all">{item.recipientEmail}</TableCell>
              <TableCell>
                <div className="grid gap-1">
                  <EmailStatus status={item.status} />
                  {item.lastError ? (
                    <details className="text-xs text-muted-foreground">
                      <summary className="cursor-pointer rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-ring">
                        <AlertTriangle className="mr-1 inline size-3" aria-hidden="true" />
                        Last error
                      </summary>
                      <p className="mt-1 max-w-xs break-words">{item.lastError}</p>
                    </details>
                  ) : null}
                </div>
              </TableCell>
              <TableCell className="text-right tabular-nums">{item.attempts}</TableCell>
              <TableCell>
                <time dateTime={item.createdAt}>{formatDateTime(item.createdAt)}</time>
              </TableCell>
              <TableCell>
                {item.sentAt ? <time dateTime={item.sentAt}>{formatDateTime(item.sentAt)}</time> : <span className="text-muted-foreground">—</span>}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
