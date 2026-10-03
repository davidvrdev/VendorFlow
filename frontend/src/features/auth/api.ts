import { apiFetch } from "@/lib/api/client";
import type { Me, SignupRequest } from "./types";

// Browser-side calls only (CSRF header attached by apiFetch). Server Components use lib/auth/session.

export const signup = (body: SignupRequest) => apiFetch<void>("/auth/signup", { method: "POST", json: body });

export const login = (body: { email: string; password: string }) =>
  apiFetch<Me>("/auth/login", { method: "POST", json: body });

export const logout = () => apiFetch<void>("/auth/logout", { method: "POST" });

export const verifyEmail = (token: string) => apiFetch<void>("/auth/verify-email", { method: "POST", json: { token } });

export const resendVerification = () => apiFetch<void>("/auth/resend-verification", { method: "POST" });

export const requestPasswordReset = (email: string) =>
  apiFetch<void>("/auth/password-reset/request", { method: "POST", json: { email } });

export const confirmPasswordReset = (token: string, newPassword: string) =>
  apiFetch<void>("/auth/password-reset/confirm", { method: "POST", json: { token, newPassword } });

export const fetchMe = () => apiFetch<Me>("/me");

export const switchOrganization = (organizationId: string) =>
  apiFetch<Me>("/session/organization", { method: "POST", json: { organizationId } });
