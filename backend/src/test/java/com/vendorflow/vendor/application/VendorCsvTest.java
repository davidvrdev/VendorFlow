package com.vendorflow.vendor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vendorflow.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the CSV cell rules and the strict decoder (no Spring, no database). */
class VendorCsvTest {

    @Test
    void protectPrefixesEveryFormulaTriggerAndNothingElse() {
        for (String trigger : new String[] {"=", "+", "-", "@", "\t", "\r"}) {
            assertThat(VendorCsv.protect(trigger + "x")).isEqualTo("'" + trigger + "x");
        }
        assertThat(VendorCsv.protect("plain")).isEqualTo("plain");
        assertThat(VendorCsv.protect("a=b")).isEqualTo("a=b");
        assertThat(VendorCsv.protect("'quoted")).isEqualTo("'quoted");
        assertThat(VendorCsv.protect(null)).isEmpty();
        assertThat(VendorCsv.protect("")).isEmpty();
    }

    @Test
    void unprotectUndoesOnlyOurOwnPrefix() {
        for (String trigger : new String[] {"=", "+", "-", "@", "\t", "\r"}) {
            assertThat(VendorCsv.unprotect(VendorCsv.protect(trigger + "x"))).isEqualTo(trigger + "x");
        }
        assertThat(VendorCsv.unprotect("'plain")).isEqualTo("'plain");
        assertThat(VendorCsv.unprotect("'")).isEqualTo("'");
        assertThat(VendorCsv.unprotect(null)).isNull();
    }

    @Test
    void decodeStripsOneBomAndRejectsInvalidUtf8() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'};
        assertThat(VendorCsvParser.decode(bom)).isEqualTo("a");
        assertThat(VendorCsvParser.decode("café".getBytes(StandardCharsets.UTF_8))).isEqualTo("café");
        assertThatThrownBy(() -> VendorCsvParser.decode(new byte[] {'a', (byte) 0xFF, 'b'}))
                .isInstanceOf(ApiException.class).hasMessageContaining("UTF-8");
        // a truncated multi-byte sequence at the end is also invalid
        assertThatThrownBy(() -> VendorCsvParser.decode(new byte[] {'a', (byte) 0xE2, (byte) 0x82}))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void parserKeepsRowNumbersAndStripsCellsAndTurnsEmptyIntoNull() {
        var parsed = VendorCsvParser.parse("Company_Name , email\r\n  A Co , a@x.example \r\n\r\nB Co,\r\n"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).cells().get("company_name")).isEqualTo("A Co");
        assertThat(parsed.rows().get(0).cells().get("email")).isEqualTo("a@x.example");
        assertThat(parsed.rows().get(1).rowNumber()).isEqualTo(3);
        assertThat(parsed.rows().get(1).cells().get("email")).isNull();
        assertThat(parsed.rows().get(1).cells().get("phone")).isNull();
    }
}
