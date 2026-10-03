import type { Metadata } from "next";
import { ComingSoonPage } from "@/components/coming-soon-page";

export const metadata: Metadata = { title: "Sign in" };

export default function LoginPage() {
  return <ComingSoonPage heading="Sign in" />;
}
