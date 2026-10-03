"use client";

import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

// Deliberately generic: error details (message, digest, stack) must never reach the user.
// Next 16.3: the retry prop is `retry` (was `unstable_retry` in 16.2, `reset` before).
export default function AppError({ retry }: { error: Error & { digest?: string }; retry: () => void }) {
  return (
    <Alert variant="destructive" className="max-w-xl">
      <AlertCircle aria-hidden="true" />
      <AlertTitle>Something went wrong</AlertTitle>
      <AlertDescription>
        <p>We could not load this page. Please try again.</p>
        <Button className="mt-3" variant="outline" onClick={() => retry()}>
          Try again
        </Button>
      </AlertDescription>
    </Alert>
  );
}
