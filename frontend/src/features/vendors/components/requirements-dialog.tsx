"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { toast } from "sonner";
import { FormAlert } from "@/components/forms/form-alert";
import { SubmitButton } from "@/components/forms/submit-button";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { ApiError } from "@/lib/api/errors";
import { errorMessage } from "@/lib/forms/api-errors";
import { setVendorRequirements } from "../api";
import type { DocumentType } from "../types";

/** Selected ids in document type order (the API treats the list as a set; stable order keeps payloads predictable). */
export function toRequirementPayload(selected: ReadonlySet<string>, documentTypes: DocumentType[]): string[] {
  return documentTypes.filter((type) => selected.has(type.id)).map((type) => type.id);
}

interface RequirementsDialogProps {
  vendorId: string;
  companyName: string;
  documentTypes: DocumentType[];
  currentIds: string[];
  /**
   * Requirements whose type was deactivated. They are listed greyed and disabled, and are NOT part of the payload:
   * the API only accepts active type ids and drops requirements that are absent from the list (API.md Phase 2 notes),
   * so saving removes them. Sending the ids we display therefore means the active, checked ones.
   */
  inactiveRequirements?: { id: string; name: string }[];
}

export function RequirementsDialog({ vendorId, companyName, documentTypes, currentIds, inactiveRequirements = [] }: RequirementsDialogProps) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(() => new Set(currentIds));
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function onOpenChange(next: boolean) {
    if (pending) return; // do not close mid-request
    if (next) {
      // Always start from what the server currently says, discarding any abandoned edits.
      setSelected(new Set(currentIds));
      setError(null);
    }
    setOpen(next);
  }

  function toggle(id: string, checked: boolean) {
    setSelected((previous) => {
      const next = new Set(previous);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await setVendorRequirements(vendorId, toRequirementPayload(selected, documentTypes));
      toast.success("Required documents updated.");
      setOpen(false);
      router.refresh();
    } catch (caught) {
      const fieldMessage = caught instanceof ApiError ? caught.errors?.find((e) => e.field === "documentTypeIds")?.message : undefined;
      setError(fieldMessage ?? errorMessage(caught, { 403: "You do not have permission to change requirements." }));
    } finally {
      setPending(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm">
          Edit requirements
        </Button>
      </DialogTrigger>
      <DialogContent>
        <form onSubmit={save} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Edit required documents</DialogTitle>
            <DialogDescription>Choose the documents {companyName} must provide.</DialogDescription>
          </DialogHeader>
          <FormAlert message={error} />
          <fieldset className="grid max-h-80 gap-1 overflow-y-auto" disabled={pending}>
            <legend className="sr-only">Required documents</legend>
            {documentTypes.map((type) => {
              const id = `requirement-${type.id}`;
              return (
                <div key={type.id} className="flex items-center gap-3 rounded-md px-1 py-1.5">
                  <Checkbox id={id} checked={selected.has(type.id)} onCheckedChange={(checked) => toggle(type.id, checked === true)} />
                  <Label htmlFor={id} className="flex-1 cursor-pointer font-normal">
                    {type.name}
                    {type.hasExpiration ? <span className="ml-2 text-xs text-muted-foreground">Expires</span> : null}
                  </Label>
                </div>
              );
            })}
            {inactiveRequirements.map((requirement) => {
              const id = `requirement-inactive-${requirement.id}`;
              return (
                <div key={requirement.id} className="flex items-start gap-3 rounded-md px-1 py-1.5 opacity-70">
                  <Checkbox id={id} checked disabled aria-describedby={`${id}-note`} className="mt-0.5" />
                  <Label htmlFor={id} className="flex-1 font-normal">
                    {requirement.name}
                    <span id={`${id}-note`} className="block text-xs text-muted-foreground">
                      Inactive type, ignored for compliance. Saving removes it from this vendor.
                    </span>
                  </Label>
                </div>
              );
            })}
          </fieldset>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={pending} onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <SubmitButton pending={pending} pendingLabel="Saving…">
              Save requirements
            </SubmitButton>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
