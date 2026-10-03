package com.vendorflow.vendor.api;

import com.vendorflow.shared.validation.PlainText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /vendors and PUT /vendors/{id}: only the editable fields (status and ids are not accepted; unknown JSON
 * properties are ignored, so a client cannot smuggle them in). Strings are stripped and optional blanks become null
 * BEFORE validation, so "  " is "absent" and limits apply to what is stored.
 */
public record VendorRequest(
        @NotBlank @Size(max = 200) @PlainText String companyName,
        @Size(max = 120) @PlainText String contactName,
        @Size(max = 254) @Email @PlainText String email,
        @Size(max = 40) @PlainText @Pattern(regexp = "(?i)(?:[0-9+().\\- ]|ext|x)+",
                message = "may only contain digits, spaces and + ( ) . - x ext") String phone,
        @Size(max = 60) @PlainText String category,
        @Size(max = 5000) @PlainText(allowLineBreaks = true) String notes) {

    public VendorRequest {
        companyName = strip(companyName);
        contactName = blankToNull(contactName);
        email = blankToNull(email);
        phone = blankToNull(phone);
        category = blankToNull(category);
        notes = blankToNull(notes);
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static String blankToNull(String value) {
        String stripped = strip(value);
        return stripped == null || stripped.isEmpty() ? null : stripped;
    }
}
