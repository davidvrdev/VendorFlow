import Link from "next/link";
import { EmptyState } from "@/components/empty-state";

/** Honest Phase 0 placeholder for routes whose feature ships later (no fake forms). */
export function ComingSoonPage({ heading }: { heading: string }) {
  return (
    <main id="main" className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center px-4 py-16">
      <h1 className="sr-only">{heading}</h1>
      <EmptyState
        title="Coming soon"
        description="Accounts are not available yet. This page will go live in an upcoming release."
      >
        <Link href="/" className="text-sm font-medium underline underline-offset-4">
          Back to home
        </Link>
      </EmptyState>
    </main>
  );
}
