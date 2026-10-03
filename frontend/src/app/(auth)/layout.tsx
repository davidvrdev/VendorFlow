import Link from "next/link";
import type { ReactNode } from "react";

// Calm, centered card for every public auth screen. `id="main"` is the skip-link target.
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main id="main" tabIndex={-1} className="flex flex-1 flex-col items-center justify-center bg-muted/40 px-4 py-12 outline-none">
      <Link href="/" className="mb-6 text-lg font-semibold tracking-tight">
        VendorFlow
      </Link>
      <div className="w-full max-w-md">{children}</div>
    </main>
  );
}
