import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { signupSchema } from "@/features/auth/schemas";
import { SignupForm } from "./signup-form";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, refresh: vi.fn() }) }));

function fillAll(overrides: Partial<Record<"fullName" | "email" | "password" | "organizationName", string>> = {}) {
  const values = {
    fullName: "Sam Rivera",
    email: "sam@example.com",
    password: "a-long-passphrase-123",
    organizationName: "Acme HOA",
    ...overrides,
  };
  fireEvent.change(screen.getByLabelText("Full name"), { target: { value: values.fullName } });
  fireEvent.change(screen.getByLabelText("Work email"), { target: { value: values.email } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: values.password } });
  fireEvent.change(screen.getByLabelText("Organization name"), { target: { value: values.organizationName } });
  fireEvent.click(screen.getByRole("button", { name: "Create account" }));
}

describe("signupSchema", () => {
  const ok = { fullName: "A", email: "a@b.co", password: "x".repeat(12), organizationName: "O" };
  it("enforces 12 characters minimum and 128 characters maximum", () => {
    expect(signupSchema.safeParse({ ...ok, password: "x".repeat(11) }).success).toBe(false);
    expect(signupSchema.safeParse({ ...ok, password: "x".repeat(128) }).success).toBe(true);
    expect(signupSchema.safeParse({ ...ok, password: "x".repeat(129) }).success).toBe(false);
    expect(signupSchema.safeParse({ ...ok, password: "ñ".repeat(40) }).success).toBe(true);
  });
  it("enforces name and organization limits", () => {
    expect(signupSchema.safeParse({ ...ok, fullName: "x".repeat(101) }).success).toBe(false);
    expect(signupSchema.safeParse({ ...ok, organizationName: "x".repeat(121) }).success).toBe(false);
    expect(signupSchema.safeParse({ ...ok, organizationName: "   " }).success).toBe(false);
  });
});

describe("SignupForm", () => {
  beforeEach(() => {
    replace.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("shows the password rule as help text and validation errors for a short password", async () => {
    render(<SignupForm />);
    expect(screen.getByText(/12 to 128 characters/i)).toBeInTheDocument();
    fillAll({ password: "short" });
    expect(await screen.findByText("Password must be at least 12 characters.")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toHaveAttribute("aria-invalid", "true");
  });

  it("maps a 409 to an email field error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ title: "Email already registered" }), {
          status: 409,
          headers: { "Content-Type": "application/problem+json" },
        }),
      ),
    );
    render(<SignupForm />);
    fillAll();
    expect(await screen.findByText("An account with this email already exists.")).toBeInTheDocument();
    expect(screen.getByLabelText("Work email")).toHaveAttribute("aria-invalid", "true");
    expect(replace).not.toHaveBeenCalled();
  });

  it("goes to the dashboard on success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 201 })));
    render(<SignupForm />);
    fillAll();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard"));
  });
});
