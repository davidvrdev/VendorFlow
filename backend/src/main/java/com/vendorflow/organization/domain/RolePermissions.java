package com.vendorflow.organization.domain;

import static com.vendorflow.organization.domain.Permission.*;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The role -> permission matrix of docs/SECURITY.md section 3. Pure data; changes are reviewed in PRs. */
public final class RolePermissions {

    private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

    static {
        Set<Permission> viewer = EnumSet.of(DATA_VIEW, DOCUMENTS_DOWNLOAD, MEMBERS_VIEW);
        Set<Permission> member = EnumSet.copyOf(viewer);
        member.addAll(EnumSet.of(CONTENT_WRITE, DOCUMENTS_REVIEW));
        Set<Permission> admin = EnumSet.copyOf(member);
        admin.addAll(EnumSet.of(ARCHIVE_AND_IMPORT, REQUIREMENTS_MANAGE, MEMBERS_MANAGE, ORG_SETTINGS_MANAGE));
        Set<Permission> owner = EnumSet.copyOf(admin);
        owner.addAll(EnumSet.of(BILLING_MANAGE, OWNERSHIP_MANAGE, ORGANIZATION_DELETE));
        MATRIX.put(Role.VIEWER, Set.copyOf(viewer));
        MATRIX.put(Role.MEMBER, Set.copyOf(member));
        MATRIX.put(Role.ADMIN, Set.copyOf(admin));
        MATRIX.put(Role.OWNER, Set.copyOf(owner));
    }

    private RolePermissions() {
    }

    public static boolean has(Role role, Permission permission) {
        return MATRIX.get(role).contains(permission);
    }

    public static Set<Permission> of(Role role) {
        return MATRIX.get(role);
    }
}
