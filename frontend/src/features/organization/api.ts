import { apiFetch } from "@/lib/api/client";
import { unwrapList } from "./lists";
import type {
  Invitation,
  InvitationLookup,
  Member,
  Organization,
  Role,
  UpdateOrganizationRequest,
} from "./types";

export const getOrganization = () => apiFetch<Organization>("/organization");

export const updateOrganization = (body: UpdateOrganizationRequest) =>
  apiFetch<Organization>("/organization", { method: "PATCH", json: body });

export const listMembers = async () =>
  unwrapList(await apiFetch<Member[] | { items: Member[] }>("/organization/members"));

export const changeMemberRole = (membershipId: string, role: Role) =>
  apiFetch<Member>(`/organization/members/${encodeURIComponent(membershipId)}`, { method: "PATCH", json: { role } });

export const removeMember = (membershipId: string) =>
  apiFetch<void>(`/organization/members/${encodeURIComponent(membershipId)}`, { method: "DELETE" });

export const listInvitations = async () =>
  unwrapList(await apiFetch<Invitation[] | { items: Invitation[] }>("/organization/invitations"));

export const createInvitation = (body: { email: string; role: Exclude<Role, "OWNER"> }) =>
  apiFetch<Invitation>("/organization/invitations", { method: "POST", json: body });

export const revokeInvitation = (id: string) =>
  apiFetch<void>(`/organization/invitations/${encodeURIComponent(id)}`, { method: "DELETE" });

// Tokens travel in the JSON body only, never in a URL (docs/API.md).
export const lookupInvitation = (token: string) =>
  apiFetch<InvitationLookup>("/invitations/lookup", { method: "POST", json: { token } });

export const acceptInvitation = (body: { token: string; fullName?: string; password?: string }) =>
  apiFetch<unknown>("/invitations/accept", { method: "POST", json: body });
