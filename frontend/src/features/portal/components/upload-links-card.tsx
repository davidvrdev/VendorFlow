"use client";

import { Link2 } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { can } from "@/features/organization/permissions";
import type { Role } from "@/features/organization/types";
import { formatDateTime } from "@/lib/format";
import { errorMessage } from "@/lib/forms/api-errors";
import { listUploadLinks, revokeUploadLink } from "../api";
import type { UploadLink, UploadLinkPage } from "../schemas";
import { SendUploadLinkDialog, type LinkRequirement } from "./send-upload-link-dialog";
import { UploadLinkStatusBadge } from "./upload-link-status-badge";

interface UploadLinksCardProps {
  vendorId: string;
  vendorName: string;
  vendorEmail: string | null | undefined;
  vendorActive: boolean;
  requirements: LinkRequirement[];
  role: Role;
}

type Loaded = { key: string; page: UploadLinkPage } | { key: string; error: string };

/** "Upload links" section of the vendor page: create (Send upload link), list (paginated, loaded in the browser), revoke. */
export function UploadLinksCard({ vendorId, vendorName, vendorEmail, vendorActive, requirements, role }: UploadLinksCardProps) {
  const canWrite = can(role, "VENDORS_WRITE"); // cosmetic; the backend enforces CONTENT_WRITE
  const [page, setPage] = useState(0);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<Loaded | null>(null);
  const [sendOpen, setSendOpen] = useState(false);
  const [revoking, setRevoking] = useState<UploadLink | null>(null);
  const key = `${page}:${reload}`;
  const loading = result?.key !== key;

  useEffect(() => {
    let cancelled = false;
    listUploadLinks(vendorId, page)
      .then((loaded) => !cancelled && setResult({ key, page: loaded }))
      .catch((error: unknown) => !cancelled && setResult({ key, error: errorMessage(error) }));
    return () => {
      cancelled = true;
    };
  }, [vendorId, page, key]);

  const data = result && "page" in result && result.key === key ? result.page : null;
  const error = result && "error" in result && result.key === key ? result.error : null;

  async function revoke(link: UploadLink) {
    await revokeUploadLink(vendorId, link.id);
    toast.success("Upload link revoked.");
    setReload((value) => value + 1);
  }

  return (
    <Card id="upload-links">
      <CardHeader>
        <CardTitle>
          <h2>Upload links</h2>
        </CardTitle>
        {canWrite ? (
          <CardAction>
            <Button variant="outline" disabled={!vendorActive} onClick={() => setSendOpen(true)}>
              <Link2 aria-hidden="true" data-icon="inline-start" />
              Send upload link
            </Button>
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent className="grid gap-4">
        <p className="text-sm text-muted-foreground">
          Private links let the vendor upload documents without an account. Uploads arrive as pending review.
        </p>
        {loading ? (
          <div role="status" aria-label="Loading upload links" className="grid gap-2">
            <Skeleton className="h-14 w-full" />
            <Skeleton className="h-14 w-full" />
          </div>
        ) : error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}{" "}
            <button type="button" className="underline" onClick={() => setReload((value) => value + 1)}>
              Try again
            </button>
          </p>
        ) : data && data.items.length === 0 ? (
          <p className="text-sm text-muted-foreground">No upload links yet.</p>
        ) : data ? (
          <>
            <ul className="divide-y rounded-lg border" aria-label="Upload links">
              {data.items.map((link) => (
                <li key={link.id} className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
                  <div className="grid min-w-0 gap-1">
                    <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                      <UploadLinkStatusBadge status={link.status} />
                      <span>{link.documentTypes.map((type) => type.name).join(", ") || "No document types"}</span>
                    </p>
                    <p className="text-xs text-muted-foreground">
                      Created {formatDateTime(link.createdAt)}
                      {link.createdBy ? ` by ${link.createdBy.fullName}` : ""} · Expires {formatDateTime(link.expiresAt)}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      Uploads: {link.useCount} of {link.maxUploads}
                      {link.lastUsedAt ? ` · Last used ${formatDateTime(link.lastUsedAt)}` : ""}
                    </p>
                  </div>
                  {canWrite && link.status === "ACTIVE" ? (
                    <Button size="sm" variant="outline" onClick={() => setRevoking(link)} aria-label={`Revoke link created ${formatDateTime(link.createdAt)}`}>
                      Revoke
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
            {data.totalPages > 1 ? (
              <nav aria-label="Upload links pagination" className="flex flex-wrap items-center justify-between gap-3 text-sm">
                <span className="text-muted-foreground" role="status">
                  Page {data.page + 1} of {data.totalPages} · {data.totalItems} links
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
      </CardContent>

      {canWrite ? (
        <SendUploadLinkDialog
          open={sendOpen}
          onOpenChange={setSendOpen}
          vendorId={vendorId}
          vendorName={vendorName}
          vendorEmail={vendorEmail}
          requirements={requirements}
          onCreated={() => {
            setPage(0);
            setReload((value) => value + 1);
          }}
        />
      ) : null}
      <ConfirmDialog
        open={revoking !== null}
        onOpenChange={(next) => !next && setRevoking(null)}
        title="Revoke this upload link?"
        description="The link stops working immediately. Documents already uploaded are kept. This cannot be undone; you can create a new link at any time."
        confirmLabel="Revoke link"
        pendingLabel="Revoking…"
        onConfirm={() => (revoking ? revoke(revoking) : Promise.resolve())}
        describeError={(e) => errorMessage(e, { 403: "You do not have permission to revoke upload links." })}
      />
    </Card>
  );
}
