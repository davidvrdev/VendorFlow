package com.vendorflow.shared.tenant;

/** Fixed roles (ADR-0008). Permissions per role are code: see {@link RolePermissions}. */
public enum Role {
    OWNER, ADMIN, MEMBER, VIEWER
}
