package com.vendorflow.shared.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled (outbox dispatcher, rate-limit cleanup). Jobs live in-process; see ADR-0006. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
