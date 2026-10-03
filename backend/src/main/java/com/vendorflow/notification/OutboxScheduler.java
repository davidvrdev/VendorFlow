package com.vendorflow.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox. Disabled in tests (app.outbox.dispatcher.enabled=false); they call dispatchBatch() directly. */
@Component
@ConditionalOnProperty(name = "app.outbox.dispatcher.enabled", havingValue = "true")
class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxDispatcher dispatcher;

    OutboxScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${app.outbox.dispatcher.fixed-delay-ms:15000}")
    void poll() {
        try {
            dispatcher.dispatchBatch();
        } catch (RuntimeException e) {
            // A scheduler thread must never die; the next tick retries.
            log.error("Outbox dispatch failed", e);
        }
    }
}
