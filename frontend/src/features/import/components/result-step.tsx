import { CheckCircle2 } from "lucide-react";
import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";
import type { ImportResult } from "../types";
import { resultText } from "../view";

export function ResultStep({ result, onAnother }: { result: ImportResult; onAnother: () => void }) {
  return (
    <section aria-labelledby="result-heading" className="grid max-w-xl gap-4 rounded-lg border px-6 py-6">
      <h2 id="result-heading" className="flex items-center gap-2 text-lg font-semibold">
        <CheckCircle2 className="size-5 text-emerald-700 dark:text-emerald-400" aria-hidden="true" />
        Import complete
      </h2>
      <p role="status">{resultText(result)}</p>
      <div className="flex flex-wrap gap-3">
        <Link href="/vendors" className={buttonVariants()}>
          Go to vendors
        </Link>
        <button type="button" className={buttonVariants({ variant: "outline" })} onClick={onAnother}>
          Import another file
        </button>
      </div>
    </section>
  );
}
