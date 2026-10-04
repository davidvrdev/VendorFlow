package com.vendorflow.portal.domain;

/** Derived, never stored. Precedence when several apply: REVOKED, EXPIRED, EXHAUSTED, ACTIVE. */
public enum LinkStatus {
    ACTIVE, EXPIRED, REVOKED, EXHAUSTED
}
