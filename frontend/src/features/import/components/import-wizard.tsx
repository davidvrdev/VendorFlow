"use client";

import { useReducer } from "react";
import { toast } from "sonner";
import { useRouter } from "next/navigation";
import { commitImport, previewImport } from "../api";
import { describeCommitError, describePreviewError } from "../errors";
import { importReducer, initialImportState } from "../import-state";
import { PreviewStep } from "./preview-step";
import { ResultStep } from "./result-step";
import { UploadStep } from "./upload-step";

/** Thin shell: owns the reducer and the two API calls; the steps are presentational. */
export function ImportWizard() {
  const router = useRouter();
  const [state, dispatch] = useReducer(importReducer, initialImportState);

  async function upload(file: File) {
    dispatch({ type: "uploadStarted" });
    try {
      dispatch({ type: "uploadSucceeded", preview: await previewImport(file) });
    } catch (error) {
      dispatch({ type: "uploadFailed", message: describePreviewError(error) });
    }
  }

  async function commit() {
    if (state.step !== "preview") return;
    dispatch({ type: "commitStarted" });
    try {
      const result = await commitImport(state.preview.importId);
      dispatch({ type: "commitSucceeded", result });
      toast.success("Vendors imported.");
      router.refresh(); // the vendors list and dashboard are server-rendered and now stale
    } catch (error) {
      const failure = describeCommitError(error);
      dispatch({ type: "commitFailed", message: failure.message, block: failure.block });
      throw new Error(failure.message); // keeps the ConfirmDialog open with the message
    }
  }

  if (state.step === "done") return <ResultStep result={state.result} onAnother={() => dispatch({ type: "reset" })} />;
  if (state.step === "preview") {
    return (
      <PreviewStep
        state={state}
        onFilter={(filter) => dispatch({ type: "filterChanged", filter })}
        onCommit={commit}
        describeError={(error) => (error instanceof Error ? error.message : "Something went wrong.")}
        onReset={() => dispatch({ type: "reset" })}
      />
    );
  }
  return <UploadStep pending={state.pending} error={state.error} onUpload={(file) => void upload(file)} />;
}
