package com.vendorflow.shared.error;

/** One entry of the {@code errors} array in a validation problem response. */
public record FieldViolation(String field, String message) {
}
