import { describe, expect, it } from "vitest";
import { canCommit, importReducer, initialImportState, type ImportState, type PreviewState } from "./import-state";
import type { ImportPreview } from "./types";

function preview(summary: Partial<ImportPreview["summary"]> = {}): ImportPreview {
  return {
    importId: "i-1",
    expiresAt: "2030-01-01T10:00:00Z",
    summary: { total: 3, create: 2, update: 1, unchanged: 0, error: 0, ...summary },
    rows: [],
  };
}

const previewing = (summary?: Partial<ImportPreview["summary"]>): PreviewState => ({
  step: "preview",
  preview: preview(summary),
  filter: "ALL",
  committing: false,
  error: null,
  block: null,
});

describe("importReducer", () => {
  it("upload: pending then failure keeps the user on the upload step with a message", () => {
    const pending = importReducer(initialImportState, { type: "uploadStarted" });
    expect(pending).toEqual({ step: "upload", pending: true, error: null });
    expect(importReducer(pending, { type: "uploadFailed", message: "Nope" })).toEqual({ step: "upload", pending: false, error: "Nope" });
  });

  it("upload success moves to preview with the ALL filter", () => {
    const next = importReducer(initialImportState, { type: "uploadSucceeded", preview: preview() });
    expect(next).toMatchObject({ step: "preview", filter: "ALL", committing: false, block: null });
  });

  it("changes the filter only in preview", () => {
    expect(importReducer(previewing(), { type: "filterChanged", filter: "ERROR" })).toMatchObject({ filter: "ERROR" });
    expect(importReducer(initialImportState, { type: "filterChanged", filter: "ERROR" })).toBe(initialImportState);
  });

  it("commit success ends on the result step", () => {
    const committing = importReducer(previewing(), { type: "commitStarted" });
    expect(committing).toMatchObject({ committing: true });
    expect(importReducer(committing, { type: "commitSucceeded", result: { created: 2, updated: 1, unchanged: 0 } })).toEqual({
      step: "done",
      result: { created: 2, updated: 1, unchanged: 0 },
    });
  });

  it("a dead preview (expired/changed) blocks further commits; a transient failure does not", () => {
    const blocked = importReducer(previewing(), { type: "commitFailed", message: "Expired", block: "expired" }) as PreviewState;
    expect(blocked).toMatchObject({ error: "Expired", block: "expired", committing: false });
    expect(canCommit(blocked)).toBe(false);
    const transient = importReducer(previewing(), { type: "commitFailed", message: "Offline", block: null }) as PreviewState;
    expect(canCommit(transient)).toBe(true);
  });

  it("refuses to commit a preview with errors or nothing to do", () => {
    expect(canCommit(previewing({ error: 1 }))).toBe(false);
    expect(canCommit(previewing({ create: 0, update: 0, unchanged: 3 }))).toBe(false);
    const stuck = previewing({ error: 1 });
    expect(importReducer(stuck, { type: "commitStarted" })).toBe(stuck);
  });

  it("reset returns to a clean upload step from anywhere", () => {
    const states: ImportState[] = [previewing(), { step: "done", result: { created: 1, updated: 0, unchanged: 0 } }];
    for (const state of states) expect(importReducer(state, { type: "reset" })).toEqual(initialImportState);
  });

  it("ignores actions that do not fit the current step", () => {
    expect(importReducer(initialImportState, { type: "commitSucceeded", result: { created: 0, updated: 0, unchanged: 0 } })).toBe(initialImportState);
  });
});
