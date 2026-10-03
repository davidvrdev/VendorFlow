import Link from "next/link";
import { EmptyState } from "@/components/empty-state";
import { buttonVariants } from "@/components/ui/button";

// Same page for a foreign vendor id and a missing one: the response never reveals which it was.
export default function VendorNotFound() {
  return (
    <EmptyState title="Vendor not found" description="This vendor does not exist, or you do not have access to it.">
      <Link href="/vendors" className={buttonVariants({ variant: "outline" })}>
        Back to vendors
      </Link>
    </EmptyState>
  );
}
