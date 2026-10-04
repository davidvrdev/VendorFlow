"use client";

import { Eye, EyeOff } from "lucide-react";
import { useState, type ComponentProps, type ReactNode } from "react";
import { FieldShell } from "@/components/forms/field";
import { Input } from "@/components/ui/input";

type PasswordFieldProps = Omit<ComponentProps<typeof Input>, "id" | "type"> & {
  id: string;
  label: string;
  error?: string;
  help?: ReactNode;
};

/**
 * Password input with a show/hide toggle. The toggle is a real button with aria-pressed and a label that names
 * the field, so screen-reader users hear "Show password, toggle button, not pressed". It is not in the way of
 * autofill/password managers because the input keeps its own autocomplete attribute.
 */
export function PasswordField({ id, label, error, help, ...inputProps }: PasswordFieldProps) {
  const [visible, setVisible] = useState(false);
  const Icon = visible ? EyeOff : Eye;
  return (
    <FieldShell id={id} label={label} error={error} help={help}>
      {(aria) => (
        <div className="relative">
          <Input {...aria} {...inputProps} type={visible ? "text" : "password"} className="pr-10" />
          <button
            type="button"
            aria-pressed={visible}
            aria-label={`${visible ? "Hide" : "Show"} ${label.toLowerCase()}`}
            onClick={() => setVisible((v) => !v)}
            className="absolute inset-y-0 right-0 flex w-10 items-center justify-center rounded-md text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Icon className="size-4" aria-hidden="true" />
          </button>
        </div>
      )}
    </FieldShell>
  );
}
