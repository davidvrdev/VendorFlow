"use client";

import { useRouter } from "next/navigation";
import { useState, type ComponentProps } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { logout } from "@/features/auth/api";

/** POST /auth/logout, then go to /login. Logout is 204 even when already signed out. */
export function SignOutButton(props: Omit<ComponentProps<typeof Button>, "onClick" | "children">) {
  const router = useRouter();
  const [pending, setPending] = useState(false);

  async function signOut() {
    setPending(true);
    try {
      await logout();
      router.replace("/login");
      router.refresh();
    } catch {
      toast.error("Could not sign out. Please try again.");
      setPending(false);
    }
  }

  return (
    <Button {...props} onClick={signOut} disabled={pending} aria-busy={pending}>
      {pending ? "Signing out…" : "Sign out"}
    </Button>
  );
}
