package com.vendorflow.chasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.support.TestAccounts.Account;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Staff endpoints: settings, vendor state, pause/resume, activity. Happy path, validation, authz and tenant isolation. */
class ChasingApiTest extends ChasingTestBase {

    static final String SETTINGS = "/api/v1/organization/chasing";

    // ---- organization settings ----

    @Test
    void settingsDefaultToDisabledAndEveryRoleCanRead() throws Exception {
        for (Account who : List.of(owner, admin, member, viewer)) {
            who.client().get(SETTINGS).andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.cadenceDays").value(7))
                    .andExpect(jsonPath("$.maxAttempts").value(4)).andExpect(jsonPath("$.leadDays").value(30))
                    .andExpect(jsonPath("$.sendHourLocal").value(9)).andExpect(jsonPath("$.ccStaff").value(false));
        }
    }

    @Test
    void ownerAndAdminUpdateSettingsAndTheChangeIsAudited() throws Exception {
        owner.client().put(SETTINGS, settings(true, 5, 3, 45, 14, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.cadenceDays").value(5))
                .andExpect(jsonPath("$.maxAttempts").value(3)).andExpect(jsonPath("$.leadDays").value(45))
                .andExpect(jsonPath("$.sendHourLocal").value(14)).andExpect(jsonPath("$.ccStaff").value(true));
        assertThat(auditCount("chasing.settings.updated")).isEqualTo(1);
        // Saving the same values again changes nothing and writes no audit row.
        admin.client().put(SETTINGS, settings(true, 5, 3, 45, 14, true)).andExpect(status().isOk());
        assertThat(auditCount("chasing.settings.updated")).isEqualTo(1);
        admin.client().put(SETTINGS, settings(false, 5, 3, 45, 14, true)).andExpect(status().isOk());
        assertThat(auditCount("chasing.settings.updated")).isEqualTo(2);
        String metadata = jdbc.queryForObject("select metadata::text from audit_event where organization_id = ?::uuid "
                + "and action = 'chasing.settings.updated' order by created_at desc limit 1", String.class, org);
        assertThat(metadata).contains("enabled").contains("before").contains("after");
        viewer.client().get(SETTINGS).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.cadenceDays").value(5));
    }

    @Test
    void settingsValidation() throws Exception {
        List<Map<String, Object>> bad = new java.util.ArrayList<>();
        bad.add(settings(true, 2, 4, 30, 9, false));
        bad.add(settings(true, 31, 4, 30, 9, false));
        bad.add(settings(true, 7, 0, 30, 9, false));
        bad.add(settings(true, 7, 11, 30, 9, false));
        bad.add(settings(true, 7, 4, 6, 9, false));
        bad.add(settings(true, 7, 4, 91, 9, false));
        bad.add(settings(true, 7, 4, 30, -1, false));
        bad.add(settings(true, 7, 4, 30, 24, false));
        for (String missing : List.of("enabled", "cadenceDays", "maxAttempts", "leadDays", "sendHourLocal", "ccStaff")) {
            Map<String, Object> m = new LinkedHashMap<>(settings(true, 7, 4, 30, 9, false));
            m.remove(missing);
            bad.add(m);
        }
        for (Map<String, Object> body : bad) {
            owner.client().put(SETTINGS, body).andExpect(status().isBadRequest());
        }
        // Boundaries are accepted.
        owner.client().put(SETTINGS, settings(true, 3, 1, 7, 0, false)).andExpect(status().isOk());
        owner.client().put(SETTINGS, settings(true, 30, 10, 90, 23, false)).andExpect(status().isOk());
        assertThat(auditCount("chasing.settings.updated")).isEqualTo(2);
    }

    @Test
    void memberAndViewerCannotChangeSettings() throws Exception {
        member.client().put(SETTINGS, settings(true, 7, 4, 30, 9, false)).andExpect(status().isForbidden());
        viewer.client().put(SETTINGS, settings(true, 7, 4, 30, 9, false)).andExpect(status().isForbidden());
        owner.client().get(SETTINGS).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void settingsOfOneOrganizationAreInvisibleToAnother() throws Exception {
        owner.client().put(SETTINGS, settings(true, 5, 3, 45, 14, true)).andExpect(status().isOk());
        other.client().get(SETTINGS).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.cadenceDays").value(7));
        assertThat(jdbc.queryForObject("select count(*) from chasing_settings where organization_id = ?::uuid",
                Integer.class, otherOrg)).isZero();
    }

    // ---- vendor state ----

    private String statePath(Object vendor) {
        return "/api/v1/vendors/" + vendor + "/chasing";
    }

    @Test
    void vendorStateReportsStatusAndTheNextChaseTime() throws Exception {
        UUID v = missingVendor(email("state"));
        // Chasing disabled: IDLE.
        owner.client().get(statePath(v)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.paused").value(false)).andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.nextChaseAt").doesNotExist());
        enable(7, 4, 30, 9, false);
        clockTo(NY, today(NY), LocalTime.of(8, 0));
        JsonNode state = body(viewer.client().get(statePath(v)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.maxAttempts").value(4)));
        assertThat(java.time.Instant.parse(state.get("nextChaseAt").asString())).isEqualTo(
                today(NY).atTime(9, 0).atZone(ZoneId.of(NY)).toInstant());
        // After the chase the next one is a cadence later, at the send hour.
        clockTo(NY, today(NY), LocalTime.of(9, 30));
        run();
        JsonNode after = body(owner.client().get(statePath(v)).andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.lastChasedAt").exists()));
        assertThat(java.time.Instant.parse(after.get("nextChaseAt").asString())).isEqualTo(
                today(NY).plusDays(7).atTime(9, 0).atZone(ZoneId.of(NY)).toInstant());
    }

    @Test
    void pauseAndResumeAreAuditedOnlyOnARealChange() throws Exception {
        UUID v = missingVendor(email("pause"));
        member.client().put(statePath(v), Map.of("paused", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true)).andExpect(jsonPath("$.pausedReason").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("PAUSED"));
        owner.client().put(statePath(v), Map.of("paused", true)).andExpect(status().isOk());
        assertThat(auditCount("vendor.chasing.paused")).isEqualTo(1);
        owner.client().put(statePath(v), Map.of("paused", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false)).andExpect(jsonPath("$.pausedReason").doesNotExist());
        owner.client().put(statePath(v), Map.of("paused", false)).andExpect(status().isOk());
        assertThat(auditCount("vendor.chasing.resumed")).isEqualTo(1);
    }

    @Test
    void pauseValidation() throws Exception {
        UUID v = missingVendor(email("validation"));
        owner.client().put(statePath(v), Map.of()).andExpect(status().isBadRequest());
        owner.client().put(statePath(v), Map.of("paused", "maybe")).andExpect(status().isBadRequest());
        // Unknown extra fields cannot set anything (explicit record).
        owner.client().put(statePath(v), Map.of("paused", true, "episodeAttempts", 99)).andExpect(status().isOk());
        assertThat(attempts(v)).isZero();
    }

    @Test
    void viewerCannotPauseButCanRead() throws Exception {
        UUID v = missingVendor(email("viewer"));
        viewer.client().put(statePath(v), Map.of("paused", true)).andExpect(status().isForbidden());
        viewer.client().get(statePath(v)).andExpect(status().isOk());
        viewer.client().get("/api/v1/vendors/" + v + "/chases").andExpect(status().isOk());
        owner.client().get(statePath(v)).andExpect(jsonPath("$.paused").value(false));
    }

    @Test
    void foreignAndUnknownVendorsAre404ForEveryVendorEndpoint() throws Exception {
        UUID foreign = fx.vendor(otherOrg, "Foreign " + UUID.randomUUID());
        for (UUID id : List.of(foreign, UUID.randomUUID())) {
            owner.client().get(statePath(id)).andExpect(status().isNotFound());
            owner.client().put(statePath(id), Map.of("paused", true)).andExpect(status().isNotFound());
            owner.client().get("/api/v1/vendors/" + id + "/chases").andExpect(status().isNotFound());
        }
        // ... and nothing was written for the foreign vendor.
        assertThat(jdbc.queryForObject("select count(*) from vendor_chasing where vendor_id = ?", Integer.class, foreign))
                .isZero();
        // The other organization's owner can of course use their own vendor.
        other.client().get(statePath(foreign)).andExpect(status().isOk());
    }

    @Test
    void chaseActivityIsPaginatedAndNewestFirst() throws Exception {
        enable(3, 10, 30, 9, false);
        UUID v = missingVendor(email("activity"));
        clockTo(NY, today(NY), LocalTime.of(9, 30));
        for (int i = 0; i < 3; i++) {
            run();
            clockTo(NY, today(NY).plusDays(3), LocalTime.of(9, 30));
        }
        owner = new Account(accounts.login(owner.email(), com.vendorflow.support.TestAccounts.PASSWORD), owner.email(),
                owner.password(), owner.me());
        owner.client().get("/api/v1/vendors/" + v + "/chases?page=0&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].attempt").value(3))
                .andExpect(jsonPath("$.items[1].attempt").value(2));
        owner.client().get("/api/v1/vendors/" + v + "/chases?page=1&size=2").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].attempt").value(1));
        owner.client().get("/api/v1/vendors/" + v + "/chases?size=1000").andExpect(jsonPath("$.size").value(100));
        // Another organization's owner sees none of it, and the payload never has a token or an address.
        String raw = owner.client().get("/api/v1/vendors/" + v + "/chases").andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("token").doesNotContain("@vendor.example.com");
    }
}
