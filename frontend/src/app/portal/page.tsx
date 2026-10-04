import type { Metadata } from "next";
import { PortalPage } from "@/features/portal/components/portal-page";

// Public page (no auth layout). The token is read client-side from the URL fragment; nothing sensitive is server-rendered.
export const metadata: Metadata = {
  title: "Upload documents",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export default function Page() {
  return <PortalPage />;
}
