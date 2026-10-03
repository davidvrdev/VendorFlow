import { AppShell } from "@/components/app-shell";

// Phase 1 adds the session check here (redirect to /login when unauthenticated).
export default function AppLayout({ children }: LayoutProps<"/">) {
  return <AppShell>{children}</AppShell>;
}
