import type { Metadata } from "next";
import { Store, Upload } from "lucide-react";
import { EmptyState } from "@/components/empty-state";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";

export const metadata: Metadata = { title: "Dashboard" };

export default function DashboardPage() {
  return (
    <>
      <PageHeader title="Dashboard" description="Which vendors need your attention right now." />
      <EmptyState
        icon={Store}
        title="No vendors yet"
        description="Add your first vendor or import a CSV."
      >
        <Button disabled aria-describedby="vendors-unavailable">
          Add vendor
        </Button>
        <Button variant="outline" disabled aria-describedby="vendors-unavailable">
          <Upload aria-hidden="true" data-icon="inline-start" /> Import CSV
        </Button>
        <p id="vendors-unavailable" className="w-full text-xs text-muted-foreground">
          These actions are disabled until vendor management is released.
        </p>
      </EmptyState>
    </>
  );
}
