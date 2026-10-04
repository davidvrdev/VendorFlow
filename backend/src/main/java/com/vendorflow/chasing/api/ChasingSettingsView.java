package com.vendorflow.chasing.api;

import com.vendorflow.chasing.application.ChasingSettings;

/** API shape "ChasingSettings" (docs/API.md Phase 15). */
public record ChasingSettingsView(boolean enabled, int cadenceDays, int maxAttempts, int leadDays, int sendHourLocal,
        boolean ccStaff) {

    public static ChasingSettingsView from(ChasingSettings s) {
        return new ChasingSettingsView(s.enabled(), s.cadenceDays(), s.maxAttempts(), s.leadDays(), s.sendHourLocal(),
                s.ccStaff());
    }
}
