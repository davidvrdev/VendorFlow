package com.vendorflow.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;

/**
 * Human-entered single-line text (names). Rejects Unicode control characters (category Cc: CR/LF/TAB/NUL...) and
 * format characters (Cf: bidi overrides such as U+202E, zero-width characters, BOM). Names end up in email bodies and
 * UI; control characters enable header-ish/line injection and Cf characters enable visual spoofing. {@code null} is
 * valid (combine with {@code @NotBlank} when required).
 *
 * <p>Known trade-off: Cf also contains ZWJ/ZWNJ, which some scripts and emoji sequences need; rejecting them is
 * acceptable for names of vendors and property managers.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PlainTextValidator.class)
public @interface PlainText {

    /** Multi-line free text (notes): also allows LF, CR and TAB. Every other control/format character stays forbidden. */
    boolean allowLineBreaks() default false;

    String message() default "must not contain control or invisible formatting characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
