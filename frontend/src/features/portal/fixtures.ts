import type { PortalInfo, UploadLink } from "./schemas";

export const portalInfo = (overrides: Partial<PortalInfo> = {}): PortalInfo => ({
  organizationName: "Sunrise HOA",
  vendorName: "Acme Plumbing",
  expiresAt: "2030-02-01T00:00:00Z",
  remainingUploads: 5,
  acceptingUploads: true,
  documentTypes: [
    { id: "t1", name: "Certificate of Insurance", hasExpiration: true, status: "MISSING", expirationDate: null },
    { id: "t2", name: "W-9", hasExpiration: false, status: "REVIEW_REQUIRED", expirationDate: null },
  ],
  ...overrides,
});

export const uploadLink = (overrides: Partial<UploadLink> = {}): UploadLink => ({
  id: "l1",
  vendorId: "v1",
  documentTypes: [{ id: "t1", name: "Certificate of Insurance" }],
  status: "ACTIVE",
  createdBy: { fullName: "Ana Owner" },
  createdAt: "2030-01-01T10:00:00Z",
  expiresAt: "2030-01-15T10:00:00Z",
  revokedAt: null,
  lastUsedAt: null,
  useCount: 1,
  maxUploads: 20,
  ...overrides,
});
