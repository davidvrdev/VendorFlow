import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface ListPaginationProps {
  /** 1-based current page. */
  page: number;
  totalPages: number;
  totalItems: number;
  noun: string;
  /** Build the href for a 1-based page number (keeps the other filters). */
  hrefFor: (page: number) => string;
}

/** Previous/Next as links (work without JS, keep filters in the URL) plus "Page X of Y" and a total. */
export function ListPagination({ page, totalPages, totalItems, noun, hrefFor }: ListPaginationProps) {
  const pages = Math.max(totalPages, 1);
  const hasPrevious = page > 1;
  const hasNext = page < pages;
  const disabledClass = cn(buttonVariants({ variant: "outline", size: "sm" }), "pointer-events-none opacity-50");
  return (
    <nav aria-label={`${noun} pagination`} className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm">
      <p className="text-muted-foreground" role="status">
        {totalItems} {totalItems === 1 ? noun : `${noun}s`}
      </p>
      <div className="flex items-center gap-3">
        <span className="text-muted-foreground">
          Page {page} of {pages}
        </span>
        {hasPrevious ? (
          <Link href={hrefFor(page - 1)} className={buttonVariants({ variant: "outline", size: "sm" })}>
            Previous
          </Link>
        ) : (
          <span aria-disabled="true" className={disabledClass}>
            Previous
          </span>
        )}
        {hasNext ? (
          <Link href={hrefFor(page + 1)} className={buttonVariants({ variant: "outline", size: "sm" })}>
            Next
          </Link>
        ) : (
          <span aria-disabled="true" className={disabledClass}>
            Next
          </span>
        )}
      </div>
    </nav>
  );
}
