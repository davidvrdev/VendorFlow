package com.vendorflow.compliance;

import com.vendorflow.compliance.domain.ComplianceCalculator.DocState;
import com.vendorflow.compliance.domain.RequirementStatus;
import com.vendorflow.compliance.domain.VendorCompliance;
import com.vendorflow.document.domain.ReviewStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Loads the shared vectors (src/test/resources/compliance/*.csv) for the Java and the SQL tests. */
final class ComplianceVectors {

    private ComplianceVectors() {
    }

    /** One requirement vector. {@code review} null = no CURRENT document. */
    record Vector(String name, boolean hasExpiration, ReviewStatus review, Integer offset, int windowDays,
            RequirementStatus expected, boolean countsNext) {

        Optional<DocState> doc(LocalDate today) {
            return review == null ? Optional.empty()
                    : Optional.of(new DocState(review, offset == null ? null : today.plusDays(offset)));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    record ReqSpec(boolean hasExpiration, ReviewStatus review, Integer offset) {

        Optional<DocState> doc(LocalDate today) {
            return review == null ? Optional.empty()
                    : Optional.of(new DocState(review, offset == null ? null : today.plusDays(offset)));
        }
    }

    record VendorVector(String name, List<ReqSpec> requirements, int windowDays, VendorCompliance expected,
            int missing, int expired, int expiring, int reviewRequired, int ok, Integer nextOffset) {

        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> requirementVectors() {
        List<Vector> out = new ArrayList<>();
        for (String[] c : rows("/compliance/vectors.csv")) {
            out.add(new Vector(c[0], Boolean.parseBoolean(c[1]), review(c[2]), integer(c[3]), Integer.parseInt(c[4]),
                    RequirementStatus.valueOf(c[5]), Boolean.parseBoolean(c[6])));
        }
        return out;
    }

    static List<VendorVector> vendorVectors() {
        List<VendorVector> out = new ArrayList<>();
        for (String[] c : rows("/compliance/vendor-vectors.csv")) {
            List<ReqSpec> reqs = new ArrayList<>();
            if (!c[1].isEmpty()) {
                for (String spec : c[1].split(";")) {
                    String[] p = spec.split(":", -1);
                    reqs.add(new ReqSpec(p[0].equals("E"), review(p[1]), integer(p[2])));
                }
            }
            out.add(new VendorVector(c[0], reqs, Integer.parseInt(c[2]), VendorCompliance.valueOf(c[3]),
                    Integer.parseInt(c[4]), Integer.parseInt(c[5]), Integer.parseInt(c[6]), Integer.parseInt(c[7]),
                    Integer.parseInt(c[8]), integer(c[9])));
        }
        return out;
    }

    private static ReviewStatus review(String s) {
        return s.equals("none") ? null : ReviewStatus.valueOf(s);
    }

    private static Integer integer(String s) {
        return s.isEmpty() ? null : Integer.valueOf(s);
    }

    /** Data rows (comments and header skipped); fields split on ',' keeping trailing empties. */
    private static List<String[]> rows(String resource) {
        try (var in = ComplianceVectors.class.getResourceAsStream(resource)) {
            List<String[]> out = new ArrayList<>();
            boolean header = true;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                out.add(line.split(",", -1));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
