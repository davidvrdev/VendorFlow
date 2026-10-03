package com.vendorflow.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PlainTextValidator implements ConstraintValidator<PlainText, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || value.codePoints().noneMatch(PlainTextValidator::isForbidden);
    }

    static boolean isForbidden(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT;
    }
}
