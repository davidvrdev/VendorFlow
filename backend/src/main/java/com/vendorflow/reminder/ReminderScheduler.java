package com.vendorflow.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 15 minutes: lets {@link ReminderService} look at every organization (each does real work at most once per
 * org-local day, after 07:00 local). Off in the test and e2e profiles (app.reminders.enabled=false): tests call the
 * service directly with a controllable clock.
 */
@Component
@ConditionalOnProperty(name = "app.reminders.enabled", havingValue = "true")
class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminders;

    ReminderScheduler(ReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${app.reminders.fixed-delay-ms:900000}",
            initialDelayString = "${app.reminders.initial-delay-ms:60000}")
    void tick() {
        try {
            reminders.runDue();
        } catch (RuntimeException e) {
            log.error("Reminder run failed", e);
        }
    }
}
