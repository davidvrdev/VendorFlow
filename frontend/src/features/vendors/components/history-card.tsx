import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDateTime } from "@/lib/format";
import { describeHistoryEvent } from "../history";
import type { HistoryEvent } from "../types";
import { ListPagination } from "./list-pagination";

interface HistoryCardProps {
  events: HistoryEvent[];
  /** document type code -> display name, so "W9" can read "W-9". */
  typeNames: Record<string, string>;
  page: number;
  totalPages: number;
  totalItems: number;
  hrefFor: (page: number) => string;
}

/** Newest first (the API order). Server-rendered; pagination is plain links via ?historyPage=. */
export function HistoryCard({ events, typeNames, page, totalPages, totalItems, hrefFor }: HistoryCardProps) {
  return (
    <Card id="history">
      <CardHeader>
        <CardTitle>
          <h2>History</h2>
        </CardTitle>
      </CardHeader>
      <CardContent>
        {events.length === 0 ? (
          <p className="text-sm text-muted-foreground">No activity yet.</p>
        ) : (
          <ol className="grid gap-4">
            {events.map((event) => {
              const { label, details } = describeHistoryEvent(event, typeNames);
              return (
                <li key={event.id} className="grid gap-1 border-l-2 pl-4">
                  <p className="text-sm font-medium">{label}</p>
                  {details.length > 0 ? (
                    <ul className="grid gap-0.5 text-sm text-muted-foreground">
                      {details.map((detail) => (
                        <li key={detail} className="break-words">
                          {detail}
                        </li>
                      ))}
                    </ul>
                  ) : null}
                  <p className="text-xs text-muted-foreground">
                    {event.actor?.fullName ?? "System"} · <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
                  </p>
                </li>
              );
            })}
          </ol>
        )}
        {totalPages > 1 ? <ListPagination page={page} totalPages={totalPages} totalItems={totalItems} noun="event" hrefFor={hrefFor} /> : null}
      </CardContent>
    </Card>
  );
}
