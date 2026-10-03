import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { can } from "@/features/organization/permissions";
import type { Role } from "@/features/organization/types";
import type { DocumentType, VendorDetail, VendorRequirement } from "../types";
import { RequirementsDialog } from "./requirements-dialog";

// One row per requirement. Phase 3/4 add the uploaded document and its compliance status as further cells
// of this row (and header labels in the grid template), so keep the row self-contained.
function RequirementRow({ requirement }: { requirement: VendorRequirement }) {
  return (
    <li className="flex flex-wrap items-center justify-between gap-2 py-2.5">
      <span className="text-sm font-medium">{requirement.name}</span>
      {requirement.hasExpiration ? <span className="text-xs text-muted-foreground">Expires</span> : null}
    </li>
  );
}

interface RequirementsCardProps {
  vendor: Pick<VendorDetail, "id" | "companyName" | "requirements">;
  documentTypes: DocumentType[];
  role: Role;
}

export function RequirementsCard({ vendor, documentTypes, role }: RequirementsCardProps) {
  const canManage = can(role, "REQUIREMENTS_MANAGE");
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Required documents</h2>
        </CardTitle>
        {canManage ? (
          <CardAction>
            <RequirementsDialog
              vendorId={vendor.id}
              companyName={vendor.companyName}
              documentTypes={documentTypes}
              currentIds={vendor.requirements.map((requirement) => requirement.documentTypeId)}
            />
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent>
        {vendor.requirements.length === 0 ? (
          <p className="text-sm text-muted-foreground">No documents are required for this vendor.</p>
        ) : (
          <ul className="divide-y">
            {vendor.requirements.map((requirement) => (
              <RequirementRow key={requirement.documentTypeId} requirement={requirement} />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
