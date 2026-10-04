package com.vendorflow.organization;

import static com.vendorflow.organization.domain.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.organization.domain.Permission;
import com.vendorflow.shared.tenant.Role;
import com.vendorflow.organization.domain.RolePermissions;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The permission matrix of docs/SECURITY.md section 3, spelled out. A change here must be a deliberate doc change. */
class RolePermissionsTest {

    private static Set<Permission> set(Permission... permissions) {
        return permissions.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.of(permissions[0], permissions);
    }

    @Test
    void viewerMayOnlyViewAndDownload() {
        assertThat(RolePermissions.of(Role.VIEWER)).containsExactlyInAnyOrderElementsOf(
                set(DATA_VIEW, DOCUMENTS_DOWNLOAD, MEMBERS_VIEW));
    }

    @Test
    void memberAddsWritingAndReview() {
        assertThat(RolePermissions.of(Role.MEMBER)).containsExactlyInAnyOrderElementsOf(
                set(DATA_VIEW, DOCUMENTS_DOWNLOAD, MEMBERS_VIEW, CONTENT_WRITE, DOCUMENTS_REVIEW));
    }

    @Test
    void adminAddsArchiveRequirementsMembersAndSettings() {
        assertThat(RolePermissions.of(Role.ADMIN)).containsExactlyInAnyOrderElementsOf(
                set(DATA_VIEW, DOCUMENTS_DOWNLOAD, MEMBERS_VIEW, CONTENT_WRITE, DOCUMENTS_REVIEW,
                        ARCHIVE_AND_IMPORT, REQUIREMENTS_MANAGE, MEMBERS_MANAGE, ORG_SETTINGS_MANAGE));
    }

    @Test
    void ownerHasEverything() {
        assertThat(RolePermissions.of(Role.OWNER)).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(Permission.class));
    }

    @Test
    void onlyOwnerHasBillingOwnershipAndDeletion() {
        for (Permission p : set(BILLING_MANAGE, OWNERSHIP_MANAGE, ORGANIZATION_DELETE)) {
            assertThat(RolePermissions.has(Role.OWNER, p)).isTrue();
            assertThat(RolePermissions.has(Role.ADMIN, p)).isFalse();
            assertThat(RolePermissions.has(Role.MEMBER, p)).isFalse();
            assertThat(RolePermissions.has(Role.VIEWER, p)).isFalse();
        }
    }

    @Test
    void everyPermissionBelongsToAtLeastOneRole() {
        for (Permission p : Permission.values()) {
            assertThat(RolePermissions.has(Role.OWNER, p)).as(p.name()).isTrue();
        }
    }
}
