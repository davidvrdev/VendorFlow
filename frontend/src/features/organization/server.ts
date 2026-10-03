import "server-only";
import { serverApiFetch } from "@/lib/api/server";
import type { Invitation, Member, Organization, Page } from "./types";

// Server Component reads for the settings pages (cookies forwarded by serverApiFetch).
export const fetchOrganization = () => serverApiFetch<Organization>("/organization");

// First page only (size defaults to 50): the UI shows "Showing N of M" when there are more.
export const fetchMembers = () => serverApiFetch<Page<Member>>("/organization/members");

export const fetchInvitations = () => serverApiFetch<Page<Invitation>>("/organization/invitations");
