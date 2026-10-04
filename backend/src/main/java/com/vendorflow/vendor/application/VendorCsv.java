package com.vendorflow.vendor.application;

import java.util.List;

/** Column names and cell-level rules shared by the CSV export, the template and the import parser. */
final class VendorCsv {

    static final String COMPANY_NAME = "company_name";
    static final String CONTACT_NAME = "contact_name";
    static final String EMAIL = "email";
    static final String PHONE = "phone";
    static final String CATEGORY = "category";
    static final String NOTES = "notes";
    static final String STATUS = "status";

    /** Columns an import reads (order of the template and of the export). */
    static final List<String> IMPORT_COLUMNS = List.of(COMPANY_NAME, CONTACT_NAME, EMAIL, PHONE, CATEGORY, NOTES,
            STATUS);

    /** Computed columns the export adds; an import accepts and ignores them so an export can be re-imported. */
    static final List<String> READ_ONLY_COLUMNS = List.of("compliance_status", "missing", "expired", "expiring",
            "review_required", "next_expiration");

    static final String CONTENT_TYPE = "text/csv; charset=utf-8";

    private VendorCsv() {
    }

    /**
     * CSV/formula-injection defence for cells we WRITE: a spreadsheet treats a cell starting with = + - @ TAB or CR as
     * a formula, so such a cell is prefixed with an apostrophe (shown by Excel/Sheets as plain text, not displayed).
     */
    static String protect(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return isTrigger(value.charAt(0)) ? "'" + value : value;
    }

    /**
     * Inverse of {@link #protect} for cells we READ: an apostrophe followed by a trigger character was added by our
     * own export (so that "+1 555 0100" survives export, edit and re-import unchanged). Any other leading apostrophe
     * is real data.
     */
    static String unprotect(String value) {
        if (value != null && value.length() > 1 && value.charAt(0) == '\'' && isTrigger(value.charAt(1))) {
            return value.substring(1);
        }
        return value;
    }

    private static boolean isTrigger(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }
}
