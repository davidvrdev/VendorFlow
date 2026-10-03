import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Role } from "@/features/organization/types";
import type { DocumentSummary, DocumentType } from "../types";
import { DocumentCells } from "./document-cells";
import { DocumentRowMenu } from "./document-row-menu";

interface OtherDocumentsCardProps {
  vendor: { id: string; companyName: string; otherDocuments: DocumentSummary[] };
  documentTypes: DocumentType[];
  currentByType: Record<string, DocumentSummary>;
  role: Role;
}

/** CURRENT documents whose type is not a requirement of this vendor. Hidden when there are none. */
export function OtherDocumentsCard({ vendor, documentTypes, currentByType, role }: OtherDocumentsCardProps) {
  if (vendor.otherDocuments.length === 0) return null;
  const activeIds = new Set(documentTypes.map((type) => type.id));
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Other documents</h2>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <caption className="sr-only">Documents that are not required for this vendor</caption>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">Document type</TableHead>
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
              {vendor.otherDocuments.map((document) => (
                <TableRow key={document.id}>
                  <TableCell className="font-medium">{document.documentType.name}</TableCell>
                  <DocumentCells document={document} />
                  <TableCell className="text-right">
                    <DocumentRowMenu
                      vendorId={vendor.id}
                      companyName={vendor.companyName}
                      role={role}
                      typeName={document.documentType.name}
                      typeId={document.documentType.id}
                      document={document}
                      typeActive={activeIds.has(document.documentType.id)}
                      documentTypes={documentTypes}
                      currentByType={currentByType}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  );
}
