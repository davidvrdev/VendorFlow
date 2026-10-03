import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";

export default function NotFound() {
  return (
    <main
      id="main"
      className="mx-auto flex w-full max-w-md flex-1 flex-col items-center justify-center px-4 py-16 text-center"
    >
      <p className="text-sm font-medium text-muted-foreground">404</p>
      <h1 className="mt-2 text-2xl font-semibold tracking-tight">Page not found</h1>
      <p className="mt-2 text-sm text-muted-foreground">The page you are looking for does not exist or has moved.</p>
      <Link href="/" className={buttonVariants({ className: "mt-6" })}>
        Back to home
      </Link>
    </main>
  );
}
