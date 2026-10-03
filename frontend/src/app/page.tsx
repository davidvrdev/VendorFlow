import { AlertTriangle, ArrowRight, Bell, CalendarClock, ClipboardList, Store } from "lucide-react";
import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";

const STEPS = [
  { icon: Store, title: "Vendor", text: "Every company you work with, in one list." },
  { icon: ClipboardList, title: "Required documents", text: "COIs, licenses, W-9s and contracts each vendor must have." },
  { icon: AlertTriangle, title: "Status", text: "Missing, OK, expiring, expired or waiting for review." },
  { icon: CalendarClock, title: "Expiration", text: "Dates tracked for you, so nothing lapses unnoticed." },
  { icon: Bell, title: "Action", text: "A prioritized list of who to chase and what to do next." },
];

export default function Home() {
  return (
    <>
      <header className="border-b">
        <div className="mx-auto flex h-16 max-w-5xl items-center justify-between px-4 sm:px-6">
          <span className="text-lg font-semibold tracking-tight">VendorFlow</span>
          <nav aria-label="Account" className="flex items-center gap-2">
            <Link href="/login" className={buttonVariants({ variant: "ghost" })}>
              Sign in
            </Link>
            <Link href="/signup" className={buttonVariants()}>
              Start free trial
            </Link>
          </nav>
        </div>
      </header>

      <main id="main" className="flex-1">
        <section className="mx-auto max-w-5xl px-4 py-16 sm:px-6 sm:py-24">
          <h1 className="max-w-3xl text-4xl font-semibold tracking-tight text-balance sm:text-5xl">
            Know which vendors need your attention.
          </h1>
          <p className="mt-6 max-w-2xl text-lg text-muted-foreground">
            Upload your vendor list. VendorFlow tells you what&apos;s missing, what&apos;s expiring, and who you need to
            chase. Built for property managers, HOA managers and facilities managers.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Link href="/signup" className={buttonVariants({ size: "lg" })}>
              Start free trial <ArrowRight aria-hidden="true" data-icon="inline-end" />
            </Link>
          </div>
        </section>

        <section aria-labelledby="how-it-works" className="border-t bg-muted/40">
          <div className="mx-auto max-w-5xl px-4 py-16 sm:px-6">
            <h2 id="how-it-works" className="text-2xl font-semibold tracking-tight">
              How it works
            </h2>
            <ol className="mt-8 grid gap-6 sm:grid-cols-2 lg:grid-cols-5">
              {STEPS.map(({ icon: Icon, title, text }, index) => (
                <li key={title} className="flex flex-col gap-2">
                  <span className="flex size-9 items-center justify-center rounded-md border bg-background">
                    <Icon className="size-4" aria-hidden="true" />
                  </span>
                  <h3 className="text-sm font-semibold">
                    <span className="sr-only">Step {index + 1}: </span>
                    {title}
                  </h3>
                  <p className="text-sm text-muted-foreground">{text}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>
      </main>

      <footer className="border-t">
        <p className="mx-auto max-w-5xl px-4 py-6 text-sm text-muted-foreground sm:px-6">VendorFlow</p>
      </footer>
    </>
  );
}
