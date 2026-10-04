package com.vendorflow.vendor.application;

import com.vendorflow.shared.error.ApiException;
import com.vendorflow.shared.error.FieldViolation;
import com.vendorflow.shared.error.RequestValidationException;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;

/**
 * Turns the bytes of an uploaded CSV into rows of cells. Knows the file format only (encoding, quoting, header, row
 * limit); the vendor rules live in VendorRequest, so they stay identical to the vendor API.
 *
 * <p>Failures that make the whole file unusable (not UTF-8, not parseable CSV, header problems, too many rows) are
 * thrown as problems; everything else is reported per row by the caller. Exception messages never contain file
 * content.
 */
final class VendorCsvParser {

    static final int MAX_ROWS = 2_000;
    /** Header width cap: the format has 13 known columns, so more than 30 is never a real vendor file. */
    static final int MAX_COLUMNS = 30;
    /** At most this many "Unknown column" errors are reported (plus one summary), each echoing at most 50 chars. */
    static final int MAX_UNKNOWN_REPORTED = 20;
    static final int MAX_ECHOED_NAME = 50;
    /** Longest accepted cell; the longest real field (notes) is 5,000, so this only bounds what we ever hold. */
    static final int MAX_CELL_CHARS = 10_000;

    /** {@code cells}: import column to stripped value, null when empty. {@code stray}: data outside the named columns. */
    record Row(int rowNumber, Map<String, String> cells, boolean stray, List<String> tooLong) {
    }

    /** {@code unknownColumns}: header names that are neither import nor read-only columns (reported on row 1). */
    record Parsed(List<String> unknownColumns, int moreUnknown, List<Row> rows) {
    }

    private VendorCsvParser() {
    }

    static Parsed parse(byte[] bytes) {
        String text = decode(bytes);
        CSVFormat format = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(false).get();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) {
                throw invalid("The file is empty. Use the template to start.");
            }
            Header header = readHeader(records.next());
            List<Row> rows = new ArrayList<>();
            int rowNumber = 0;
            while (records.hasNext()) {
                CSVRecord record = records.next();
                rowNumber++;
                if (isBlank(record)) {
                    // Blank lines and rows of only commas (spreadsheet leftovers) are skipped but still numbered,
                    // so row numbers match what the user sees in the spreadsheet.
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "import-too-many-rows",
                            "Too many rows", "The file has more than " + MAX_ROWS
                                    + " data rows. Split it into smaller files.");
                }
                rows.add(toRow(rowNumber, record, header));
            }
            if (rows.isEmpty()) {
                throw invalid("The file has a header but no data rows.");
            }
            return new Parsed(header.unknown(), header.moreUnknown(), rows);
        } catch (IOException | UncheckedIOException | IllegalStateException | IllegalArgumentException e) {
            // commons-csv reports malformed quoting this way; its message may quote file content, so it is not used.
            throw invalid("The file is not valid CSV (check the quotation marks near the reported line).");
        }
    }

    /** Strips an optional BOM, then decodes strictly: invalid bytes are an error, never silently replaced. */
    static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF
                ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw invalid("The file is not valid UTF-8. In Excel choose Save As > CSV UTF-8 and upload it again.");
        }
    }

    private record Header(Map<String, Integer> indexes, int width, List<String> unknown, int moreUnknown,
            Set<Integer> ignoredIndexes) {
    }

    private static Header readHeader(CSVRecord record) {
        if (record.size() > MAX_COLUMNS) {
            // Checked first: nothing below may do per-column work (or echo names) for a hostile header.
            throw invalid("The header has " + record.size() + " columns; at most " + MAX_COLUMNS
                    + " are allowed. Use the template.");
        }
        Map<String, Integer> indexes = new HashMap<>();
        Set<Integer> ignored = new HashSet<>();
        Set<String> seen = new HashSet<>();
        List<String> unknown = new ArrayList<>();
        int moreUnknown = 0;
        List<FieldViolation> errors = new ArrayList<>();
        for (int i = 0; i < record.size(); i++) {
            String name = record.get(i).strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue; // empty header cell (trailing commas): data under it is reported as "stray"
            }
            if (!seen.add(name)) {
                errors.add(new FieldViolation(echo(name), "Duplicate column in the header"));
                continue;
            }
            if (VendorCsv.IMPORT_COLUMNS.contains(name)) {
                indexes.put(name, i);
            } else if (VendorCsv.READ_ONLY_COLUMNS.contains(name)) {
                ignored.add(i);
            } else {
                if (unknown.size() < MAX_UNKNOWN_REPORTED) {
                    unknown.add(echo(name));
                } else {
                    moreUnknown++;
                }
                ignored.add(i);
            }
        }
        if (!indexes.containsKey(VendorCsv.COMPANY_NAME)) {
            errors.add(new FieldViolation(VendorCsv.COMPANY_NAME, "Required column is missing from the header"));
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        return new Header(indexes, record.size(), unknown, moreUnknown, ignored);
    }

    private static Row toRow(int rowNumber, CSVRecord record, Header header) {
        Map<String, String> cells = new LinkedHashMap<>();
        List<String> tooLong = new ArrayList<>();
        for (String column : VendorCsv.IMPORT_COLUMNS) {
            Integer index = header.indexes().get(column);
            String raw = index == null || index >= record.size() ? null : record.get(index);
            if (raw != null && raw.length() > MAX_CELL_CHARS) {
                // The oversized value is dropped here, never stored or echoed; the row is reported as an error.
                tooLong.add(column);
                cells.put(column, null);
                continue;
            }
            String value = raw == null ? null : VendorCsv.unprotect(raw.strip());
            cells.put(column, value == null || value.isBlank() ? null : value);
        }
        boolean stray = false;
        for (int i = 0; i < record.size(); i++) {
            boolean named = header.indexes().containsValue(i) || header.ignoredIndexes().contains(i);
            if (!named && !record.get(i).isBlank()) {
                stray = true;
            }
        }
        return new Row(rowNumber, cells, stray, tooLong);
    }

    /** Header names are echoed in errors: cap their length so a hostile header cannot inflate the response. */
    static String echo(String name) {
        return name.length() <= MAX_ECHOED_NAME ? name : name.substring(0, MAX_ECHOED_NAME);
    }

    private static boolean isBlank(CSVRecord record) {
        for (String value : record) {
            if (!value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid-csv", "Invalid CSV file", detail);
    }
}
