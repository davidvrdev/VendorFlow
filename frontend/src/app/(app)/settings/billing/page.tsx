import type { Metadata } from "next";
import { Suspense } from "react";
import { BillingPanel } from "@/features/billing/components/billing-panel";

export const metadata: Metadata = { title: "Billing" };

// Subscription state lives in the app shell's provider (shared with the banner); useSearchParams needs Suspense.
export default function BillingSettingsPage() {
  return (
    <Suspense fallback={null}>
      <BillingPanel />
    </Suspense>
  );
}
