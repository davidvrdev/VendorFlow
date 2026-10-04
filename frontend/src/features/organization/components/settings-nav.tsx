"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { can } from "../permissions";
import type { Role } from "../types";

const ITEMS = [
  { href: "/settings/organization", label: "Organization" },
  { href: "/settings/members", label: "Members" },
];

// Cosmetic: the page itself redirects roles without REQUIREMENTS_MANAGE and the API enforces it.
const DOCUMENT_TYPES_ITEM = { href: "/settings/document-types", label: "Document types" };

const EMAIL_ACTIVITY_ITEM = { href: "/settings/email-activity", label: "Email activity" };

const BILLING_ITEM = { href: "/settings/billing", label: "Billing" };

/** Pure: which tabs a role sees. Cosmetic: pages redirect and the API enforces. */
export function settingsNavItems(role: Role | null | undefined): { href: string; label: string }[] {
  return [
    ...ITEMS,
    ...(can(role, "REQUIREMENTS_MANAGE") ? [DOCUMENT_TYPES_ITEM] : []),
    ...(can(role, "EMAIL_ACTIVITY_VIEW") ? [EMAIL_ACTIVITY_ITEM] : []),
    // Every member may see status; only owners get buttons (the page says "ask an owner").
    BILLING_ITEM,
  ];
}

export function SettingsNav({ role }: { role?: Role | null }) {
  const items = settingsNavItems(role);
  const pathname = usePathname();
  return (
    <nav aria-label="Settings" className="mb-8 border-b">
      <ul className="-mb-px flex gap-4">
        {items.map(({ href, label }) => {
          const active = pathname === href || pathname.startsWith(`${href}/`);
          return (
            <li key={href}>
              <Link
                href={href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "inline-block rounded-sm border-b-2 border-transparent px-1 py-2 text-sm font-medium text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring",
                  active && "border-foreground text-foreground",
                )}
              >
                {label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
