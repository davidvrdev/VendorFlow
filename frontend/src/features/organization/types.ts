// Mirrors docs/API.md "Phase 1 contract details". Keep in sync with the backend DTOs.
export type Role = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";

export interface OrganizationRef {
  id: string;
  name: string;
  role: Role;
}

export interface Organization {
  id: string;
  name: string;
  timeZone: string;
  expiringWindowDays: number;
  reminderOffsetsDays: number[];
  remindersEnabled: boolean;
  createdAt: string;
}

export interface Member {
  membershipId: string;
  userId: string;
  fullName: string;
  email: string;
  role: Role;
  joinedAt: string;
}

export interface Invitation {
  id: string;
  email: string;
  role: Exclude<Role, "OWNER">;
  expiresAt: string;
  createdAt: string;
  invitedBy: { fullName: string } | null;
}

export interface InvitationLookup {
  organizationName: string;
  role: Role;
  email: string;
  accountExists: boolean;
}

export interface UpdateOrganizationRequest {
  name?: string;
  timeZone?: string;
  expiringWindowDays?: number;
  reminderOffsetsDays?: number[];
  remindersEnabled?: boolean;
}

/** Page envelope returned by every list endpoint (docs/API.md). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}
