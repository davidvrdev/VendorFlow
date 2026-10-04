import type { Chase, ChasePage, ChasingSettings, OptOutInfo, VendorChasingState } from "./schemas";

export const chasingSettings = (overrides: Partial<ChasingSettings> = {}): ChasingSettings => ({
  enabled: false,
  cadenceDays: 7,
  maxAttempts: 4,
  leadDays: 30,
  sendHourLocal: 9,
  ccStaff: false,
  ...overrides,
});

export const chasingState = (overrides: Partial<VendorChasingState> = {}): VendorChasingState => ({
  paused: false,
  pausedReason: null,
  lastChasedAt: "2030-01-10T14:00:00Z",
  attempts: 1,
  maxAttempts: 4,
  nextChaseAt: "2030-01-17T14:00:00Z",
  status: "ACTIVE",
  ...overrides,
});

export const chase = (overrides: Partial<Chase> = {}): Chase => ({
  id: "c1",
  date: "2030-01-10",
  attempt: 1,
  types: [{ id: "t1", name: "Certificate of Insurance", status: "MISSING" }],
  createdAt: "2030-01-10T14:00:00Z",
  linkStatus: "ACTIVE",
  emailStatus: "SENT",
  ...overrides,
});

export const chasePage = (items: Chase[] = [chase()], overrides: Partial<ChasePage> = {}): ChasePage => ({
  items,
  page: 0,
  size: 10,
  totalItems: items.length,
  totalPages: items.length === 0 ? 0 : 1,
  ...overrides,
});

export const optOutInfo = (overrides: Partial<OptOutInfo> = {}): OptOutInfo => ({
  organizationName: "Sunrise HOA",
  vendorName: "Acme Plumbing",
  optedOut: false,
  ...overrides,
});
