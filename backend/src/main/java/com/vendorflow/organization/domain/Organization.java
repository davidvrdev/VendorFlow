package com.vendorflow.organization.domain;

import com.vendorflow.shared.persistence.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization")
public class Organization extends UuidEntity {

    public static final String DEFAULT_TIME_ZONE = "America/New_York";

    @Column(nullable = false)
    private String name;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "expiring_window_days", nullable = false)
    private int expiringWindowDays;

    @Column(name = "reminder_offsets_days", nullable = false)
    private int[] reminderOffsetsDays;

    @Column(name = "reminders_enabled", nullable = false)
    private boolean remindersEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Organization() {
    }

    public Organization(String name, Instant now) {
        super(UUID.randomUUID());
        this.name = name;
        this.timeZone = DEFAULT_TIME_ZONE;
        this.expiringWindowDays = 30;
        this.reminderOffsetsDays = new int[] {30, 14, 7, 1};
        this.remindersEnabled = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getName() {
        return name;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public int getExpiringWindowDays() {
        return expiringWindowDays;
    }

    public int[] getReminderOffsetsDays() {
        return reminderOffsetsDays.clone();
    }

    public boolean isRemindersEnabled() {
        return remindersEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public void setExpiringWindowDays(int expiringWindowDays) {
        this.expiringWindowDays = expiringWindowDays;
    }

    public void setReminderOffsetsDays(int[] reminderOffsetsDays) {
        this.reminderOffsetsDays = reminderOffsetsDays.clone();
    }

    public void setRemindersEnabled(boolean remindersEnabled) {
        this.remindersEnabled = remindersEnabled;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }
}
