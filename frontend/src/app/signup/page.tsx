import type { Metadata } from "next";
import { ComingSoonPage } from "@/components/coming-soon-page";

export const metadata: Metadata = { title: "Start free trial" };

export default function SignupPage() {
  return <ComingSoonPage heading="Start your free trial" />;
}
