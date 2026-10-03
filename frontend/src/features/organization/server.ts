import "server-only";
import { serverApiFetch } from "@/lib/api/server";
import { unwrapList } from "./lists";
import type { Invitation, Member, Organization } from "./types";

// Server Component reads for the settings pages (cookies forwarded by serverApiFetch).
export const fetchOrganization = () => serverApiFetch<Organization>("/organization");

export async function fetchMembers(): Promise<Member[]> {
  return unwrapList(await serverApiFetch<Member[] | { items: Member[] }>("/organization/members"));
}

export async function fetchInvitations(): Promise<Invitation[]> {
  return unwrapList(await serverApiFetch<Invitation[] | { items: Invitation[] }>("/organization/invitations"));
}
