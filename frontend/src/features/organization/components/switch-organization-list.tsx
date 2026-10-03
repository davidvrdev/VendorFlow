"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { switchOrganization } from "@/features/auth/api";
import { errorMessage } from "@/lib/forms/api-errors";
import { FormAlert } from "@/components/forms/form-alert";
import { ROLE_LABELS } from "../permissions";
import type { OrganizationRef } from "../types";

/** Shown on /no-organization when the session has no active organization but other memberships exist. */
export function SwitchOrganizationList({ organizations }: { organizations: OrganizationRef[] }) {
  const router = useRouter();
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function choose(id: string) {
    setPendingId(id);
    setError(null);
    try {
      await switchOrganization(id);
      router.replace("/dashboard");
      router.refresh();
    } catch (e) {
      setError(errorMessage(e));
      setPendingId(null);
    }
  }

  return (
    <div className="grid w-full max-w-sm gap-3 text-left">
      <FormAlert message={error} />
      <ul className="grid gap-2">
        {organizations.map((org) => (
          <li key={org.id} className="flex items-center justify-between gap-3 rounded-lg border p-3">
            <span className="min-w-0">
              <span className="block truncate text-sm font-medium">{org.name}</span>
              <span className="text-xs text-muted-foreground">{ROLE_LABELS[org.role]}</span>
            </span>
            <Button size="sm" onClick={() => void choose(org.id)} disabled={pendingId !== null} aria-busy={pendingId === org.id}>
              {pendingId === org.id ? "Switching…" : "Switch to this organization"}
              <span className="sr-only"> {org.name}</span>
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}
