import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/errors";
import { chasingSettings } from "../fixtures";
import { ChasingSettingsForm } from "./chasing-settings-form";

const api = vi.hoisted(() => ({ saveChasingSettings: vi.fn() }));
vi.mock("../api", () => api);
vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
const refresh = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
vi.mock("sonner", () => ({ toast }));

beforeEach(() => {
  api.saveChasingSettings.mockReset();
  refresh.mockReset();
  toast.success.mockReset();
});

const renderForm = (canEdit = true, settings = chasingSettings()) =>
  render(<ChasingSettingsForm settings={settings} timeZone="America/Chicago" canEdit={canEdit} />);

describe("ChasingSettingsForm", () => {
  it("explains what vendors receive and shows the org time zone and daily-summary note", () => {
    renderForm();
    expect(screen.getByText(/private upload link and a link to stop these reminders/)).toBeInTheDocument();
    expect(screen.getByText(/unsubscribe link is paused automatically/)).toBeInTheDocument();
    expect(screen.getByText(/America\/Chicago/)).toBeInTheDocument();
    expect(screen.getByText(/contains no upload links/)).toBeInTheDocument();
    expect(screen.getByLabelText("Send automatic follow-ups to vendors")).not.toBeChecked();
  });

  it("validates ranges with field errors and does not call the API", async () => {
    renderForm();
    fireEvent.click(screen.getByLabelText("Send automatic follow-ups to vendors"));
    fireEvent.change(screen.getByLabelText("Days between follow-ups"), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText("Send hour (0 to 23)"), { target: { value: "24" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("Days between follow-ups must be a whole number from 3 to 30.")).toBeInTheDocument();
    expect(screen.getByText("Send hour must be a whole number from 0 to 23.")).toBeInTheDocument();
    expect(api.saveChasingSettings).not.toHaveBeenCalled();
  });

  it("saves the full body, shows success and refreshes", async () => {
    api.saveChasingSettings.mockResolvedValue(chasingSettings({ enabled: true, sendHourLocal: 0 }));
    renderForm();
    expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled(); // nothing changed yet
    fireEvent.click(screen.getByLabelText("Send automatic follow-ups to vendors"));
    fireEvent.change(screen.getByLabelText("Send hour (0 to 23)"), { target: { value: "0" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Follow-up settings saved."));
    expect(api.saveChasingSettings).toHaveBeenCalledWith({ enabled: true, cadenceDays: 7, maxAttempts: 4, leadDays: 30, sendHourLocal: 0, ccStaff: false });
    expect(refresh).toHaveBeenCalled();
  });

  it("disables the button while saving", async () => {
    let finish: (value: unknown) => void = () => {};
    api.saveChasingSettings.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    renderForm();
    fireEvent.click(screen.getByLabelText("Email owners and admins a daily summary"));
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("button", { name: "Saving…" })).toBeDisabled();
    finish(chasingSettings({ ccStaff: true }));
    await waitFor(() => expect(toast.success).toHaveBeenCalled());
  });

  it("maps server field errors and shows a form-level message for 403", async () => {
    api.saveChasingSettings.mockRejectedValueOnce(new ApiError({ status: 400, title: "Invalid", errors: [{ field: "cadenceDays", message: "must be between 3 and 30" }] }));
    renderForm();
    fireEvent.click(screen.getByLabelText("Email owners and admins a daily summary"));
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("must be between 3 and 30")).toBeInTheDocument();

    api.saveChasingSettings.mockRejectedValueOnce(new ApiError({ status: 403, title: "Forbidden" }));
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("You do not have permission to change follow-up settings.")).toBeInTheDocument();
  });

  it("is read-only for roles that cannot manage settings", () => {
    renderForm(false);
    expect(screen.getByText("Only owners and admins can change follow-up settings.")).toBeInTheDocument();
    expect(screen.getByLabelText("Days between follow-ups")).toBeDisabled();
    expect(screen.getByLabelText("Send automatic follow-ups to vendors")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save changes" })).not.toBeInTheDocument();
  });
});
