import type { Metadata } from "next";
import { InviteFlow } from "@/features/organization/components/invite-flow";

export const metadata: Metadata = {
  title: "Invitation",
  // The page URL carries a one-time token in the fragment: keep it out of Referer headers and indexes.
  referrer: "no-referrer",
  robots: { index: false },
};

export default function InvitePage() {
  return <InviteFlow />;
}
