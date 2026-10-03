package com.vendorflow.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PlainTextValidator implements ConstraintValidator<PlainText, CharSequence> {

    private boolean allowLineBreaks;

    @Override
    public void initialize(PlainText annotation) {
        this.allowLineBreaks = annotation.allowLineBreaks();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || value.codePoints().noneMatch(cp -> isForbidden(cp, allowLineBreaks));
    }

    static boolean isForbidden(int codePoint) {
        return isForbidden(codePoint, false);
    }

    private static boolean isForbidden(int codePoint, boolean allowLineBreaks) {
        if (allowLineBreaks && (codePoint == '\n' || codePoint == '\r' || codePoint == '\t')) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT;
    }
}
