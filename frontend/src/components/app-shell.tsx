"use client";

import { LayoutDashboard, Menu, Settings, Store, X, type LucideIcon } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { UserMenu } from "@/features/auth/components/user-menu";
import { VerifyEmailBanner } from "@/features/auth/components/verify-email-banner";
import type { Me } from "@/features/auth/types";
import type { OrganizationRef } from "@/features/organization/types";
import { OrgSwitcher } from "@/features/organization/components/org-switcher";
import { cn } from "@/lib/utils";

const NAV_ITEMS: { href: string; label: string; icon: LucideIcon }[] = [
  { href: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "/vendors", label: "Vendors", icon: Store },
  { href: "/settings", label: "Settings", icon: Settings },
];

function isActive(pathname: string, href: string) {
  return pathname === href || pathname.startsWith(`${href}/`);
}

interface AppShellProps {
  me: Me;
  activeOrganization: OrganizationRef;
  children: ReactNode;
}

export function AppShell({ me, activeOrganization, children }: AppShellProps) {
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="flex min-h-full flex-1 flex-col md:flex-row">
      <aside className="border-b bg-sidebar text-sidebar-foreground md:w-60 md:shrink-0 md:border-r md:border-b-0">
        <div className="flex h-14 items-center justify-between px-4">
          <Link href="/dashboard" className="text-lg font-semibold tracking-tight">
            VendorFlow
          </Link>
          <Button
            variant="ghost"
            size="icon"
            className="md:hidden"
            aria-expanded={menuOpen}
            aria-controls="primary-nav"
            onClick={() => setMenuOpen((open) => !open)}
          >
            {menuOpen ? <X aria-hidden="true" /> : <Menu aria-hidden="true" />}
            <span className="sr-only">{menuOpen ? "Close menu" : "Open menu"}</span>
          </Button>
        </div>
        {/* On mobile everything below the top bar collapses; from md up it is always visible. */}
        <div className={cn("flex-col gap-3 px-2 pb-3 md:flex md:h-[calc(100%-3.5rem)] md:pb-3", menuOpen ? "flex" : "hidden")}>
        <OrgSwitcher active={activeOrganization} organizations={me.organizations} />
        <nav id="primary-nav" aria-label="Primary" className="flex-1">
          <ul className="flex flex-col gap-1">
            {NAV_ITEMS.map(({ href, label, icon: Icon }) => {
              const active = isActive(pathname, href);
              return (
                <li key={href}>
                  <Link
                    href={href}
                    aria-current={active ? "page" : undefined}
                    onClick={() => setMenuOpen(false)}
                    className={cn(
                      "flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium outline-none hover:bg-sidebar-accent focus-visible:ring-2 focus-visible:ring-ring",
                      active && "bg-sidebar-accent text-sidebar-accent-foreground",
                    )}
                  >
                    <Icon className="size-4" aria-hidden="true" />
                    {label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>
        <UserMenu user={me.user} />
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        {me.user.emailVerified ? null : <VerifyEmailBanner />}
        <main id="main" tabIndex={-1} className="min-w-0 flex-1 px-4 py-8 outline-none sm:px-8">
          {children}
        </main>
      </div>
    </div>
  );
}
