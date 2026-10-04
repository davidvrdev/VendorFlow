package com.vendorflow.chasing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly tick (minute 5). Each organization does real work at most once per org-local day (ledger), at or after its
 * send hour. Off in the test and e2e profiles (app.chasing.enabled=false): tests call the service with a controllable clock.
 */
@Component
@ConditionalOnProperty(name = "app.chasing.enabled", havingValue = "true")
class ChasingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChasingScheduler.class);

    private final ChasingService chasing;

    ChasingScheduler(ChasingService chasing) {
        this.chasing = chasing;
    }

    @Scheduled(cron = "${app.chasing.cron:0 5 * * * *}")
    void tick() {
        try {
            chasing.runDue();
        } catch (RuntimeException e) {
            log.error("Chasing tick failed: error={}", e.getClass().getSimpleName());
        }
    }
}
