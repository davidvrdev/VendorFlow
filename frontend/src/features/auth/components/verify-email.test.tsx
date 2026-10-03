import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { VerifyEmail } from "./verify-email";

const refresh = vi.fn();
const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, refresh }) }));

function problem(status: number, body: object) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/problem+json" } });
}

describe("VerifyEmail", () => {
  beforeEach(() => {
    refresh.mockReset();
    replace.mockReset();
    document.cookie = "XSRF-TOKEN=t; path=/";
    window.history.replaceState(null, "", "/verify-email#token=abc");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
    window.history.replaceState(null, "", "/");
  });

  it("posts the token once, shows success and refreshes the router so the banner disappears", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<VerifyEmail />);
    expect(await screen.findByText("Email verified")).toBeInTheDocument();
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenCalledWith("/verify-email");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ token: "abc" });
    expect(window.location.hash).toBe("");
  });

  it("400 means invalid or expired link", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(400, { title: "Invalid or expired link" })));
    render(<VerifyEmail />);
    expect(await screen.findByText("This verification link is invalid or has expired.")).toBeInTheDocument();
    await waitFor(() => expect(refresh).not.toHaveBeenCalled());
  });

  it("shows the server message for other failures (e.g. 429)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(problem(429, { title: "Too many requests", detail: "Slow down." })));
    render(<VerifyEmail />);
    expect(await screen.findByText("Slow down.")).toBeInTheDocument();
  });
});
