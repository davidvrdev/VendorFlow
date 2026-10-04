import type { Metadata } from "next";
import { ChangePasswordForm } from "@/features/auth/components/change-password-form";

export const metadata: Metadata = { title: "Account settings" };

// Any signed-in member may change their own password; the API takes the user from the session.
export default function AccountSettingsPage() {
  return (
    <section aria-labelledby="change-password-heading" className="grid gap-4">
      <h2 id="change-password-heading" className="text-lg font-semibold">
        Change password
      </h2>
      <ChangePasswordForm />
    </section>
  );
}
