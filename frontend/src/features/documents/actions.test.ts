import { describe, expect, it } from "vitest";
import { hasAnyAction, rowActions } from "./actions";
import { doc } from "./test-fixtures";

describe("rowActions with a current document", () => {
  it("VIEWER: download only", () => {
    expect(rowActions("VIEWER", doc())).toEqual({
      upload: false,
      replace: false,
      download: true,
      approve: false,
      reject: false,
      editDates: false,
      archive: false,
    });
  });

  it("MEMBER: replace, review and edit dates, but no archive", () => {
    const actions = rowActions("MEMBER", doc());
    expect(actions).toMatchObject({ replace: true, download: true, approve: true, reject: true, editDates: true, archive: false });
  });

  it.each(["ADMIN", "OWNER"] as const)("%s: everything", (role) => {
    expect(rowActions(role, doc())).toMatchObject({ replace: true, download: true, approve: true, reject: true, editDates: true, archive: true });
  });

  it("does not offer the decision the document already has", () => {
    expect(rowActions("ADMIN", doc({ reviewStatus: "APPROVED" }))).toMatchObject({ approve: false, reject: true });
    expect(rowActions("ADMIN", doc({ reviewStatus: "REJECTED" }))).toMatchObject({ approve: true, reject: false });
  });

  it("limits non-current documents (review and date edits need CURRENT; archived cannot be archived)", () => {
    expect(rowActions("OWNER", doc({ state: "SUPERSEDED" }))).toMatchObject({ approve: false, editDates: false, archive: true, download: true });
    expect(rowActions("OWNER", doc({ state: "ARCHIVED" }))).toMatchObject({ archive: false, download: true });
  });

  it("hides upload/replace for inactive types", () => {
    expect(rowActions("OWNER", doc(), false)).toMatchObject({ replace: false, download: true });
  });
});

describe("rowActions for a missing document", () => {
  it("offers Upload to writers only", () => {
    expect(rowActions("MEMBER", null)).toMatchObject({ upload: true, download: false, approve: false, archive: false });
    expect(hasAnyAction(rowActions("VIEWER", null))).toBe(false);
  });

  it("hides Upload for inactive types", () => {
    expect(hasAnyAction(rowActions("OWNER", null, false))).toBe(false);
  });
});

describe("rowActions with a portal CANDIDATE", () => {
  it("can be approved or rejected by a reviewer but its dates cannot be edited", () => {
    const candidate = doc({ state: "CANDIDATE", source: "PORTAL" });
    expect(rowActions("ADMIN", candidate)).toMatchObject({ approve: true, reject: true, editDates: false, download: true });
    expect(rowActions("VIEWER", candidate)).toMatchObject({ approve: false, reject: false });
  });
});
