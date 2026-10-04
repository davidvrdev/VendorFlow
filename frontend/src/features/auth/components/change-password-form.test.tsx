import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ChangePasswordForm } from "./change-password-form";
import { PasswordField } from "./password-field";

function problem(status: number, body: object) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
}

function fill(current = "old-password-1", next = "a-long-passphrase-123", confirm = next) {
  fireEvent.change(screen.getByLabelText("Current password"), { target: { value: current } });
  fireEvent.change(screen.getByLabelText("New password"), { target: { value: next } });
  fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: confirm } });
  fireEvent.click(screen.getByRole("button", { name: "Change password" }));
}

describe("ChangePasswordForm", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=t; path=/";
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("posts both passwords, shows success and clears the fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<ChangePasswordForm />);
    fill();
    expect(await screen.findByText(/Your password has been changed/)).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain("/me/password");
    expect(JSON.parse(init.body)).toEqual({ currentPassword: "old-password-1", newPassword: "a-long-passphrase-123" });
    expect(screen.getByLabelText("Current password")).toHaveValue("");
  });

  it("validates client-side without calling the API", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    render(<ChangePasswordForm />);
    fill("old", "short", "different");
    expect(await screen.findByText("Password must be at least 12 characters.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("shows a form-level error when the current password is wrong", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(401, { title: "Unauthorized" })));
    render(<ChangePasswordForm />);
    fill();
    expect(await screen.findByText("Your current password is incorrect.")).toBeInTheDocument();
  });

  it("maps server field errors (e.g. common password) onto the new password field", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        problem(400, { title: "Validation failed", errors: [{ field: "newPassword", message: "That password is too common." }] }),
      ),
    );
    render(<ChangePasswordForm />);
    fill();
    expect(await screen.findByText("That password is too common.")).toBeInTheDocument();
  });

  it("disables the button and says what is happening while pending", async () => {
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(new Promise(() => {})));
    render(<ChangePasswordForm />);
    fill();
    const button = await screen.findByRole("button", { name: "Changing password…" });
    await waitFor(() => expect(button).toBeDisabled());
  });
});

describe("PasswordField", () => {
  it("toggles visibility with an accessible pressed button and keeps the value", () => {
    render(<PasswordField id="pw" label="Password" defaultValue="secret-value" />);
    const input = screen.getByLabelText("Password");
    expect(input).toHaveAttribute("type", "password");
    const toggle = screen.getByRole("button", { name: "Show password" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(toggle);
    expect(input).toHaveAttribute("type", "text");
    expect(input).toHaveValue("secret-value");
    expect(screen.getByRole("button", { name: "Hide password" })).toHaveAttribute("aria-pressed", "true");
  });
});
