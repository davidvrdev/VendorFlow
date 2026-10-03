package com.vendorflow.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.vendorflow.compliance.ComplianceVectors.ReqSpec;
import com.vendorflow.compliance.ComplianceVectors.VendorVector;
import com.vendorflow.compliance.ComplianceVectors.Vector;
import com.vendorflow.compliance.domain.ComplianceCalculator;
import com.vendorflow.compliance.domain.ComplianceCalculator.DocState;
import com.vendorflow.compliance.domain.ComplianceCalculator.Evaluated;
import com.vendorflow.compliance.domain.ComplianceSummary;
import com.vendorflow.compliance.domain.RequirementStatus;
import com.vendorflow.document.domain.ReviewStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The pure Java side of the shared vectors (the SQL side is ComplianceListSqlTest). No Spring, no database. */
class ComplianceCalculatorVectorsTest {

    static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    static Stream<Vector> requirementVectors() {
        return ComplianceVectors.requirementVectors().stream();
    }

    static Stream<VendorVector> vendorVectors() {
        return ComplianceVectors.vendorVectors().stream();
    }

    @Test
    void vectorFilesCoverEveryStatusAndAreBigEnough() {
        List<Vector> vectors = ComplianceVectors.requirementVectors();
        assertThat(vectors.size()).isGreaterThanOrEqualTo(25);
        assertThat(vectors.stream().map(Vector::expected).distinct()).containsExactlyInAnyOrder(
                RequirementStatus.values());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requirementVectors")
    void requirementStatus(Vector v) {
        assertThat(ComplianceCalculator.evaluate(v.hasExpiration(), v.doc(TODAY), TODAY, v.windowDays()))
                .isEqualTo(v.expected());
        boolean counted = ComplianceCalculator.countedExpiration(v.hasExpiration(), v.doc(TODAY)) != null;
        assertThat(counted).isEqualTo(v.countsNext());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vendorVectors")
    void vendorAggregation(VendorVector v) {
        List<Evaluated> evaluated = new ArrayList<>();
        for (ReqSpec r : v.requirements()) {
            evaluated.add(ComplianceCalculator.evaluated(r.hasExpiration(), r.doc(TODAY), TODAY, v.windowDays()));
        }
        ComplianceSummary s = ComplianceCalculator.summarize(evaluated, TODAY);
        assertThat(s.status()).isEqualTo(v.expected());
        assertThat(List.of(s.missing(), s.expired(), s.expiring(), s.reviewRequired(), s.ok()))
                .containsExactly(v.missing(), v.expired(), v.expiring(), v.reviewRequired(), v.ok());
        assertThat(s.nextExpiration()).isEqualTo(v.nextOffset() == null ? null : TODAY.plusDays(v.nextOffset()));
        assertThat(s.daysUntilNextExpiration()).isEqualTo(v.nextOffset());
    }

    @Test
    void daysUntilExpirationIsNegativeWhenExpiredAndNullWhenNotApplicable() {
        var doc = Optional.of(new DocState(ReviewStatus.APPROVED, TODAY.minusDays(3)));
        assertThat(ComplianceCalculator.daysUntilExpiration(true, doc, TODAY)).isEqualTo(-3);
        assertThat(ComplianceCalculator.daysUntilExpiration(false, doc, TODAY)).isNull();
        assertThat(ComplianceCalculator.daysUntilExpiration(true, Optional.empty(), TODAY)).isNull();
    }
}
