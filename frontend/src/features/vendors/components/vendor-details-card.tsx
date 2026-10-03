import type { ReactNode } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDate } from "@/lib/format";
import type { VendorDetail } from "../types";

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-0.5 sm:grid-cols-[10rem_1fr] sm:gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-sm break-words">{children ?? <span className="text-muted-foreground">—</span>}</dd>
    </div>
  );
}

export function VendorDetailsCard({ vendor }: { vendor: VendorDetail }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Contact &amp; details</h2>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <dl className="grid gap-3">
          <Row label="Contact">{vendor.contactName}</Row>
          <Row label="Email">
            {vendor.email ? (
              <a href={`mailto:${vendor.email}`} className="underline underline-offset-4">
                {vendor.email}
              </a>
            ) : null}
          </Row>
          <Row label="Phone">
            {vendor.phone ? (
              // tel: links want digits only; keep the displayed text as entered.
              <a href={`tel:${vendor.phone.replace(/[^0-9+]/g, "")}`} className="underline underline-offset-4">
                {vendor.phone}
              </a>
            ) : null}
          </Row>
          <Row label="Category">{vendor.category}</Row>
          <Row label="Notes">{vendor.notes ? <span className="whitespace-pre-line">{vendor.notes}</span> : null}</Row>
          <Row label="Created by">{vendor.createdBy?.fullName}</Row>
          <Row label="Created">{formatDate(vendor.createdAt)}</Row>
          <Row label="Last updated">{formatDate(vendor.updatedAt)}</Row>
        </dl>
      </CardContent>
    </Card>
  );
}
