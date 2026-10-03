import { Skeleton } from "@/components/ui/skeleton";

export default function VendorsLoading() {
  return (
    <div role="status" aria-live="polite" aria-busy="true">
      <span className="sr-only">Loading vendors</span>
      <Skeleton className="h-8 w-40" />
      <Skeleton className="mt-3 h-4 w-64" />
      <div className="mt-8 flex gap-3">
        <Skeleton className="h-8 w-72" />
        <Skeleton className="h-8 w-36" />
        <Skeleton className="h-8 w-44" />
      </div>
      <div className="mt-4 space-y-2">
        {Array.from({ length: 8 }, (_, index) => (
          <Skeleton key={index} className="h-12 w-full" />
        ))}
      </div>
    </div>
  );
}
