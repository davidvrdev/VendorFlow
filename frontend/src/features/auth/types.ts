import type { OrganizationRef } from "@/features/organization/types";

export type { Role } from "@/features/organization/types";

export interface User {
  id: string;
  email: string;
  fullName: string;
  emailVerified: boolean;
}

export interface Me {
  user: User;
  /** null => the user belongs to no organization. */
  activeOrganization: OrganizationRef | null;
  /** Sorted by name by the backend. */
  organizations: OrganizationRef[];
}

export interface SignupRequest {
  email: string;
  password: string;
  fullName: string;
  organizationName: string;
}
