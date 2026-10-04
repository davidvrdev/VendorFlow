import type { Metadata } from "next";
import { UnsubscribePage } from "@/features/chasing/components/unsubscribe-page";

// Public page (no auth layout). The token is read client-side from the URL fragment; nothing sensitive is server-rendered.
export const metadata: Metadata = {
  title: "Stop document reminders",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export default function Page() {
  return <UnsubscribePage />;
}
