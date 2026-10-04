// The import wizard as a pure reducer: upload -> preview -> done. Components only dispatch; no logic lives in JSX.

import type { CommitBlock } from "./errors";
import type { ImportPreview, ImportResult, ImportRowAction } from "./types";

export type RowFilter = "ALL" | "ERROR" | ImportRowAction;

export type PreviewState = {
  step: "preview";
  preview: ImportPreview;
  filter: RowFilter;
  committing: boolean;
  error: string | null;
  /** Set once the server said this preview can never be committed. */
  block: CommitBlock | null;
};

export type ImportState =
  | { step: "upload"; pending: boolean; error: string | null }
  | PreviewState
  | { step: "done"; result: ImportResult };

export type ImportAction =
  | { type: "uploadStarted" }
  | { type: "uploadFailed"; message: string }
  | { type: "uploadSucceeded"; preview: ImportPreview }
  | { type: "filterChanged"; filter: RowFilter }
  | { type: "commitStarted" }
  | { type: "commitFailed"; message: string; block: CommitBlock | null }
  | { type: "commitSucceeded"; result: ImportResult }
  | { type: "reset" };

export const initialImportState: ImportState = { step: "upload", pending: false, error: null };

/** Commit is possible only for an error-free, non-empty, still-valid preview that is not already committing. */
export function canCommit(state: PreviewState): boolean {
  const { summary } = state.preview;
  return !state.committing && state.block === null && summary.error === 0 && summary.create + summary.update > 0;
}

export function importReducer(state: ImportState, action: ImportAction): ImportState {
  switch (action.type) {
    case "uploadStarted":
      return state.step === "upload" ? { step: "upload", pending: true, error: null } : state;
    case "uploadFailed":
      return state.step === "upload" ? { step: "upload", pending: false, error: action.message } : state;
    case "uploadSucceeded":
      return state.step === "upload"
        ? { step: "preview", preview: action.preview, filter: "ALL", committing: false, error: null, block: null }
        : state;
    case "filterChanged":
      return state.step === "preview" ? { ...state, filter: action.filter } : state;
    case "commitStarted":
      return state.step === "preview" && canCommit(state) ? { ...state, committing: true, error: null } : state;
    case "commitFailed":
      return state.step === "preview"
        ? { ...state, committing: false, error: action.message, block: action.block ?? state.block }
        : state;
    case "commitSucceeded":
      return state.step === "preview" ? { step: "done", result: action.result } : state;
    case "reset":
      return initialImportState;
  }
}
