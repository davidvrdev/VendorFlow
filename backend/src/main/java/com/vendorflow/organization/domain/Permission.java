package com.vendorflow.organization.domain;

/** One entry per row of the permission matrix in docs/SECURITY.md section 3 (plus MEMBERS_VIEW from API.md). */
public enum Permission {
    /** View vendors, documents, dashboard. */
    DATA_VIEW,
    /** Download documents. */
    DOCUMENTS_DOWNLOAD,
    /** Create/edit vendors, upload documents, edit document metadata. */
    CONTENT_WRITE,
    /** Approve/reject documents. */
    DOCUMENTS_REVIEW,
    /** Archive vendors/documents, CSV import. */
    ARCHIVE_AND_IMPORT,
    /** Manage requirements and document types. */
    REQUIREMENTS_MANAGE,
    /** List members of the organization. */
    MEMBERS_VIEW,
    /** Invite users, change roles (never OWNER), remove members. */
    MEMBERS_MANAGE,
    /** Organization settings (reminders, time zone, name). */
    ORG_SETTINGS_MANAGE,
    /** Billing. */
    BILLING_MANAGE,
    /** Grant/revoke the OWNER role. */
    OWNERSHIP_MANAGE,
    /** Delete the organization. */
    ORGANIZATION_DELETE
}
