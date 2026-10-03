import "server-only";
import { serverApiFetch } from "@/lib/api/server";
import type { DocumentTypeAdmin } from "./types";

/** All types of the org including inactive ones (REQUIREMENTS_MANAGE only; others get 403). */
export const fetchDocumentTypesAdmin = () => serverApiFetch<DocumentTypeAdmin[]>("/document-types?includeInactive=true");
