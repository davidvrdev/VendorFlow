import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ResetPasswordForm } from "./reset-password-form";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, refresh: vi.fn() }) }));

function problem(status: number, body: object) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
}

function submit(password = "a-long-passphrase-123") {
  fireEvent.change(screen.getByLabelText("New password"), { target: { value: password } });
  fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: "Change password" }));
}

describe("ResetPasswordForm", () => {
  beforeEach(() => {
    replace.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
    window.history.replaceState(null, "", "/reset-password#token=tok-123");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
    window.history.replaceState(null, "", "/");
  });

  it("strips the token from the URL, posts it in the body and sends the user to /login on success", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<ResetPasswordForm />);
    await screen.findByLabelText("New password");
    expect(window.location.hash).toBe("");

    submit();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?reset=1"));
    const [, init] = fetchMock.mock.calls[0];
    expect(JSON.parse(init.body)).toEqual({ token: "tok-123", newPassword: "a-long-passphrase-123" });
  });

  it("400 without field errors = invalid or expired link", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(400, { title: "Invalid or expired link" })));
    render(<ResetPasswordForm />);
    await screen.findByLabelText("New password");
    submit();
    expect(await screen.findByText("This reset link is invalid or has expired.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Request a new reset link" })).toHaveAttribute("href", "/forgot-password");
    expect(replace).not.toHaveBeenCalled();
  });

  it("400 with a newPassword field error keeps the form (the token is not consumed) and shows the message", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        problem(400, { title: "Validation failed", errors: [{ field: "newPassword", message: "This password is too common." }] }),
      ),
    );
    render(<ResetPasswordForm />);
    await screen.findByLabelText("New password");
    submit();
    expect(await screen.findByText("This password is too common.")).toBeInTheDocument();
    expect(screen.getByLabelText("New password")).toHaveAttribute("aria-invalid", "true");
    expect(replace).not.toHaveBeenCalled();
  });

  it("shows the rate-limit message on 429", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(429, { title: "Too many requests" })));
    render(<ResetPasswordForm />);
    await screen.findByLabelText("New password");
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many attempts");
  });
});
