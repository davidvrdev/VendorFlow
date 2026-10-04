"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDate } from "@/lib/format";
import { errorMessage } from "@/lib/forms/api-errors";
import { listChases } from "../api";
import type { ChasePage } from "../schemas";
import { chaseDateIso, EMAIL_STATUS_LABELS, LINK_STATUS_LABELS } from "../view";

type Loaded = { key: string; page: ChasePage } | { key: string; error: string };

/** "Follow-up history": paginated, loaded in the browser. `version` changes when the chasing state does, to reload. */
export function ChaseHistory({ vendorId, version }: { vendorId: string; version: string }) {
  const [page, setPage] = useState(0);
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<Loaded | null>(null);
  const key = `${vendorId}:${page}:${retry}:${version}`;
  const loading = result?.key !== key;

  useEffect(() => {
    let cancelled = false;
    listChases(vendorId, page)
      .then((loaded) => !cancelled && setResult({ key, page: loaded }))
      .catch((error: unknown) => !cancelled && setResult({ key, error: errorMessage(error) }));
    return () => {
      cancelled = true;
    };
  }, [vendorId, page, key]);

  const data = result && "page" in result && result.key === key ? result.page : null;
  const error = result && "error" in result && result.key === key ? result.error : null;

  return (
    <section aria-labelledby="chase-history-heading" className="grid gap-3">
      <h3 id="chase-history-heading" className="text-sm font-semibold">
        Follow-up history
      </h3>
      {loading ? (
        <div role="status" aria-label="Loading follow-up history" className="grid gap-2">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}{" "}
          <button type="button" className="underline" onClick={() => setRetry((value) => value + 1)}>
            Try again
          </button>
        </p>
      ) : data && data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No follow-ups have been sent yet.</p>
      ) : data ? (
        <>
          <ul className="divide-y rounded-lg border" aria-label="Follow-up history">
            {data.items.map((chase) => (
              <li key={chase.id} className="grid gap-1 px-4 py-3 text-sm">
                <p className="font-medium">
                  {formatDate(chaseDateIso(chase.date))} · Follow-up {chase.attempt}
                </p>
                <p className="text-xs text-muted-foreground">
                  {chase.types.length === 0
                    ? "No document types"
                    : chase.types.map((type) => `${type.name} (${type.status.toLowerCase()})`).join(", ")}
                </p>
                <p className="text-xs text-muted-foreground">
                  {chase.linkStatus ? LINK_STATUS_LABELS[chase.linkStatus] : "No link"} ·{" "}
                  <span className={chase.emailStatus === "FAILED" || chase.emailStatus === "DEAD" ? "font-medium text-destructive" : undefined}>
                    {chase.emailStatus ? EMAIL_STATUS_LABELS[chase.emailStatus] : "Email status unknown"}
                  </span>
                </p>
              </li>
            ))}
          </ul>
          {data.totalPages > 1 ? (
            <nav aria-label="Follow-up history pagination" className="flex flex-wrap items-center justify-between gap-3 text-sm">
              <span className="text-muted-foreground" role="status">
                Page {data.page + 1} of {data.totalPages} · {data.totalItems} follow-ups
              </span>
              <div className="flex gap-2">
                <Button size="sm" variant="outline" disabled={data.page <= 0} onClick={() => setPage(data.page - 1)}>
                  Previous
                </Button>
                <Button size="sm" variant="outline" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage(data.page + 1)}>
                  Next
                </Button>
              </div>
            </nav>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
