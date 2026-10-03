import type { ComponentProps, ReactNode } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

interface FieldShellProps {
  id: string;
  label: string;
  error?: string;
  help?: ReactNode;
  className?: string;
  /** Receives the aria props to spread on the control. */
  children: (aria: { id: string; "aria-invalid": boolean; "aria-describedby": string | undefined }) => ReactNode;
}

/** Label + control + help + error wiring (aria-invalid / aria-describedby) for any control. */
export function FieldShell({ id, label, error, help, className, children }: FieldShellProps) {
  const helpId = help ? `${id}-help` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [helpId, errorId].filter(Boolean).join(" ") || undefined;
  return (
    <div className={cn("grid gap-1.5", className)}>
      <Label htmlFor={id}>{label}</Label>
      {children({ id, "aria-invalid": Boolean(error), "aria-describedby": describedBy })}
      {help ? (
        <p id={helpId} className="text-xs text-muted-foreground">
          {help}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="text-xs font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

type TextFieldProps = Omit<ComponentProps<typeof Input>, "id"> & {
  id: string;
  label: string;
  error?: string;
  help?: ReactNode;
  wrapperClassName?: string;
};

/** Input wired for react-hook-form: `<TextField id="email" label="Email" error={...} {...register("email")} />`. */
export function TextField({ id, label, error, help, wrapperClassName, ...inputProps }: TextFieldProps) {
  return (
    <FieldShell id={id} label={label} error={error} help={help} className={wrapperClassName}>
      {(aria) => <Input {...aria} {...inputProps} />}
    </FieldShell>
  );
}
