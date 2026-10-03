package com.vendorflow.compliance.application;

import java.time.LocalDate;

/** What every compliance computation needs besides the data: "today" in the organization zone and the window. */
public record ComplianceContext(LocalDate today, int windowDays) {
}
