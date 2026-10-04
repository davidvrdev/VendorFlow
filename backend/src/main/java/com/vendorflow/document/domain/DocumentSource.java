package com.vendorflow.document.domain;

/** Where an upload came from: a signed-in staff member, or a vendor using a portal link (ADR-0011). */
public enum DocumentSource {
    STAFF, PORTAL
}
