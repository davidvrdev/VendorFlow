package com.vendorflow.shared.error;

import java.util.List;

/** Validation that cannot be expressed as bean-validation annotations; rendered exactly like them (400 + errors). */
public class RequestValidationException extends RuntimeException {

    private final List<FieldViolation> violations;

    public RequestValidationException(List<FieldViolation> violations) {
        super("Request validation failed");
        this.violations = List.copyOf(violations);
    }

    public RequestValidationException(String field, String message) {
        this(List.of(new FieldViolation(field, message)));
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
