import { ComplianceStatusBadge } from "@/components/compliance-status-badge";
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { expirationText } from "@/features/compliance/format";
import { DocumentCells } from "@/features/documents/components/document-cells";
import { DocumentRowMenu } from "@/features/documents/components/document-row-menu";
import { UploadButton } from "@/features/documents/components/upload-button";
import type { DocumentSummary } from "@/features/documents/types";
import { can } from "@/features/organization/permissions";
import type { Role } from "@/features/organization/types";
import type { DocumentType, VendorDetail } from "../types";
import { RequirementsDialog } from "./requirements-dialog";

/** CURRENT documents of the vendor by document type id (required and other), for the replace warning. */
export function currentDocumentsByType(vendor: Pick<VendorDetail, "requirements" | "otherDocuments">): Record<string, DocumentSummary> {
  const byType: Record<string, DocumentSummary> = {};
  for (const requirement of vendor.requirements) {
    if (requirement.currentDocument) byType[requirement.documentTypeId] = requirement.currentDocument;
  }
  for (const document of vendor.otherDocuments) byType[document.documentType.id] = document;
  return byType;
}

interface RequirementsCardProps {
  vendor: Pick<VendorDetail, "id" | "companyName" | "requirements" | "otherDocuments">;
  /** ACTIVE document types (GET /document-types). A requirement whose type is missing here is inactive. */
  documentTypes: DocumentType[];
  role: Role;
}

export function RequirementsCard({ vendor, documentTypes, role }: RequirementsCardProps) {
  const canManage = can(role, "REQUIREMENTS_MANAGE");
  const canUpload = can(role, "VENDORS_WRITE") && documentTypes.length > 0;
  const activeIds = new Set(documentTypes.map((type) => type.id));
  const currentByType = currentDocumentsByType(vendor);
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Required documents</h2>
        </CardTitle>
        {canManage || canUpload ? (
          <CardAction className="flex gap-2">
            {canUpload ? (
              <UploadButton vendorId={vendor.id} companyName={vendor.companyName} documentTypes={documentTypes} currentByType={currentByType} />
            ) : null}
            {canManage ? (
              <RequirementsDialog
                vendorId={vendor.id}
                companyName={vendor.companyName}
                documentTypes={documentTypes}
                currentIds={vendor.requirements.map((requirement) => requirement.documentTypeId)}
                inactiveRequirements={vendor.requirements
                  .filter((requirement) => !activeIds.has(requirement.documentTypeId))
                  .map((requirement) => ({ id: requirement.documentTypeId, name: requirement.name }))}
              />
            ) : null}
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent>
        {vendor.requirements.length === 0 ? (
          <p className="text-sm text-muted-foreground">No documents are required for this vendor.</p>
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <caption className="sr-only">Required documents for {vendor.companyName}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">Document type</TableHead>
                  <TableHead scope="col">Status</TableHead>
                  <TableHead scope="col">Current document</TableHead>
                  <TableHead scope="col">Issue date</TableHead>
                  <TableHead scope="col">Expiration date</TableHead>
                  <TableHead scope="col">Review</TableHead>
                  <TableHead scope="col" className="text-right">
                    Actions
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {vendor.requirements.map((requirement) => {
                  const inactive = !requirement.active || !activeIds.has(requirement.documentTypeId);
                  const urgent = !inactive && (requirement.status === "MISSING" || requirement.status === "EXPIRED");
                  const days = inactive ? null : expirationText(requirement.daysUntilExpiration);
                  return (
                    <TableRow key={requirement.documentTypeId} className={inactive ? "bg-muted/40 text-muted-foreground" : urgent ? "bg-red-50/50 dark:bg-red-950/20" : undefined}>
                      <TableCell className="font-medium">
                        <span>{requirement.name}</span>
                        {requirement.hasExpiration ? <span className="ml-2 text-xs font-normal text-muted-foreground"> (expires)</span> : null}
                      </TableCell>
                      <TableCell>
                        {inactive ? (
                          <span className="text-xs">Ignored for compliance</span>
                        ) : (
                          <div className="grid gap-1">
                            <ComplianceStatusBadge status={requirement.status} className="w-fit" />
                            {days ? <span className="text-xs text-muted-foreground">{days}</span> : null}
                          </div>
                        )}
                      </TableCell>
                      <DocumentCells document={requirement.currentDocument} />
                      <TableCell className="text-right">
                        <DocumentRowMenu
                          vendorId={vendor.id}
                          companyName={vendor.companyName}
                          role={role}
                          typeName={requirement.name}
                          typeId={requirement.documentTypeId}
                          document={requirement.currentDocument}
                          typeActive={!inactive}
                          status={inactive ? undefined : requirement.status}
                          documentTypes={documentTypes}
                          currentByType={currentByType}
                        />
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
