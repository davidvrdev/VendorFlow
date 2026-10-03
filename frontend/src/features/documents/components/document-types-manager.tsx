"use client";

import { ArrowDown, ArrowUp, CheckCircle2, CircleOff } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/lib/forms/api-errors";
import { createDocumentType, updateDocumentType } from "../api";
import { computeMove, sortTypes } from "../ordering";
import type { DocumentTypeAdmin } from "../types";
import { DocumentTypeForm } from "./document-type-form";

const yesNo = (value: boolean) => (value ? "Yes" : "No");

export function DocumentTypesManager({ types }: { types: DocumentTypeAdmin[] }) {
  const router = useRouter();
  const ordered = sortTypes(types);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [deactivating, setDeactivating] = useState<DocumentTypeAdmin | null>(null);
  const [moving, setMoving] = useState(false);

  async function move(type: DocumentTypeAdmin, direction: "up" | "down") {
    const changes = computeMove(ordered, type.id, direction);
    if (changes.length === 0) return;
    setMoving(true);
    try {
      await Promise.all(changes.map((change) => updateDocumentType(change.id, { sortOrder: change.sortOrder })));
      router.refresh();
    } catch (error) {
      toast.error(errorMessage(error, { 403: "You do not have permission to manage document types." }));
      router.refresh(); // one of the two writes may have landed: show the truth
    } finally {
      setMoving(false);
    }
  }

  async function reactivate(type: DocumentTypeAdmin) {
    try {
      await updateDocumentType(type.id, { active: true });
      toast.success(`${type.name} is active again.`);
      router.refresh();
    } catch (error) {
      toast.error(errorMessage(error));
    }
  }

  return (
    <div className="grid max-w-4xl gap-6">
      <Card>
        <CardHeader>
          <CardTitle>
            <h2>Add a document type</h2>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="max-w-md">
            <DocumentTypeForm
              idPrefix="new-type"
              submitLabel="Add document type"
              pendingLabel="Adding…"
              resetOnSuccess
              onSubmit={async (values) => {
                await createDocumentType(values);
                toast.success(`${values.name} was added.`);
                router.refresh();
              }}
            />
          </div>
        </CardContent>
      </Card>

      <section aria-labelledby="types-heading">
        <h2 id="types-heading" className="mb-4 text-lg font-semibold tracking-tight">
          Document types
        </h2>
        {ordered.length === 0 ? (
          <p className="text-sm text-muted-foreground">No document types yet.</p>
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <caption className="sr-only">Document types of the organization, including inactive ones</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">Name</TableHead>
                  <TableHead scope="col">Has expiration</TableHead>
                  <TableHead scope="col">Required by default</TableHead>
                  <TableHead scope="col">Status</TableHead>
                  <TableHead scope="col">Order</TableHead>
                  <TableHead scope="col" className="text-right">
                    Actions
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {ordered.map((type, index) =>
                  editingId === type.id ? (
                    <TableRow key={type.id}>
                      <TableCell colSpan={6}>
                        <div className="max-w-md py-2">
                          <DocumentTypeForm
                            idPrefix={`edit-${type.id}`}
                            defaultValues={{ name: type.name, hasExpiration: type.hasExpiration, requiredByDefault: type.requiredByDefault }}
                            submitLabel="Save changes"
                            pendingLabel="Saving…"
                            onCancel={() => setEditingId(null)}
                            onSubmit={async (values) => {
                              await updateDocumentType(type.id, values);
                              toast.success("Document type updated.");
                              setEditingId(null);
                              router.refresh();
                            }}
                          />
                        </div>
                      </TableCell>
                    </TableRow>
                  ) : (
                    <TableRow key={type.id} className={type.active ? undefined : "bg-muted/40 text-muted-foreground"}>
                      <TableCell className="font-medium">{type.name}</TableCell>
                      <TableCell>{yesNo(type.hasExpiration)}</TableCell>
                      <TableCell>{yesNo(type.requiredByDefault)}</TableCell>
                      <TableCell>
                        {type.active ? (
                          <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200">
                            <CheckCircle2 aria-hidden="true" />
                            Active
                          </Badge>
                        ) : (
                          <Badge variant="outline">
                            <CircleOff aria-hidden="true" />
                            Inactive
                          </Badge>
                        )}
                      </TableCell>
                      <TableCell>
                        <div className="flex gap-1">
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            aria-label={`Move ${type.name} up`}
                            disabled={moving || index === 0}
                            onClick={() => void move(type, "up")}
                          >
                            <ArrowUp aria-hidden="true" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            aria-label={`Move ${type.name} down`}
                            disabled={moving || index === ordered.length - 1}
                            onClick={() => void move(type, "down")}
                          >
                            <ArrowDown aria-hidden="true" />
                          </Button>
                        </div>
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-2">
                          <Button variant="outline" size="sm" aria-label={`Edit ${type.name}`} onClick={() => setEditingId(type.id)}>
                            Edit
                          </Button>
                          {type.active ? (
                            <Button variant="outline" size="sm" aria-label={`Deactivate ${type.name}`} onClick={() => setDeactivating(type)}>
                              Deactivate
                            </Button>
                          ) : (
                            <Button variant="outline" size="sm" aria-label={`Reactivate ${type.name}`} onClick={() => void reactivate(type)}>
                              Reactivate
                            </Button>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  ),
                )}
              </TableBody>
            </Table>
          </div>
        )}
      </section>

      {deactivating ? (
        <ConfirmDialog
          open
          onOpenChange={(open) => {
            if (!open) setDeactivating(null);
          }}
          title={`Deactivate ${deactivating.name}?`}
          description="It will no longer be offered when adding requirements or uploading documents. Vendors that already require it keep the requirement, but it is ignored for compliance. You can reactivate it at any time."
          confirmLabel="Deactivate document type"
          pendingLabel="Deactivating…"
          onConfirm={async () => {
            await updateDocumentType(deactivating.id, { active: false });
            toast.success(`${deactivating.name} was deactivated.`);
            router.refresh();
          }}
          describeError={(error) => errorMessage(error, { 403: "You do not have permission to manage document types." })}
        />
      ) : null}
    </div>
  );
}
