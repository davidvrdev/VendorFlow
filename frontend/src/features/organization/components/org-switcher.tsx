"use client";

import { Building2, Check, ChevronsUpDown } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { switchOrganization } from "@/features/auth/api";
import { errorMessage } from "@/lib/forms/api-errors";
import { ROLE_LABELS } from "../permissions";
import type { OrganizationRef } from "../types";

interface OrgSwitcherProps {
  active: OrganizationRef;
  organizations: OrganizationRef[];
}

/** Shows the active organization; with several memberships it becomes a switcher. */
export function OrgSwitcher({ active, organizations }: OrgSwitcherProps) {
  const router = useRouter();
  const [pending, setPending] = useState(false);

  async function choose(organizationId: string) {
    if (organizationId === active.id) return;
    setPending(true);
    try {
      await switchOrganization(organizationId);
      // Server Components re-read /me and everything tenant-scoped for the new organization.
      router.refresh();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      setPending(false);
    }
  }

  if (organizations.length <= 1) {
    return (
      <div className="flex items-center gap-2 px-3 py-2 text-sm" data-testid="active-org">
        <Building2 className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="truncate font-medium">{active.name}</span>
      </div>
    );
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" className="w-full justify-between" disabled={pending} aria-label={`Organization: ${active.name}. Switch organization`}>
          <span className="flex min-w-0 items-center gap-2">
            <Building2 aria-hidden="true" />
            <span className="truncate">{active.name}</span>
          </span>
          <ChevronsUpDown aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-64">
        <DropdownMenuLabel>Switch organization</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {organizations.map((org) => (
          <DropdownMenuItem key={org.id} onSelect={() => void choose(org.id)}>
            <span className="min-w-0 flex-1 truncate">{org.name}</span>
            <span className="text-xs text-muted-foreground">{ROLE_LABELS[org.role]}</span>
            {org.id === active.id ? <Check aria-label="Current organization" /> : null}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
