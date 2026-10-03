package com.vendorflow.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V4 backfills the default document types for organizations that already exist. Uses its own throw-away Postgres:
 * migrate to V3, insert organizations, then migrate to the latest version (the shared test database is always
 * migrated from scratch, so it cannot exercise this path).
 */
class MigrationBackfillTest {

    @Test
    void v4GivesEveryExistingOrganizationTheEightDefaultTypes() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target("3").load().migrate();

            try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword()); Statement s = c.createStatement()) {
                s.executeUpdate("insert into organization (id, name, created_at, updated_at) values "
                        + "('00000000-0000-0000-0000-000000000001', 'Old A', now(), now()), "
                        + "('00000000-0000-0000-0000-000000000002', 'Old B', now(), now())");

                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .load().migrate();

                for (String org : List.of("00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000002")) {
                    List<String> required = new ArrayList<>();
                    int total = 0;
                    try (ResultSet rs = s.executeQuery("select code, required_by_default, has_expiration, name "
                            + "from document_type where organization_id = '" + org + "' order by sort_order")) {
                        while (rs.next()) {
                            total++;
                            if (rs.getBoolean("required_by_default")) {
                                required.add(rs.getString("code"));
                            }
                        }
                    }
                    assertThat(total).isEqualTo(8);
                    assertThat(required).containsExactly("COI", "WORKERS_COMP", "W9");
                }
                try (ResultSet rs = s.executeQuery("select name from document_type where code = 'WORKERS_COMP' "
                        + "limit 1")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("Workers' Compensation");
                }
            }
        }
    }
}
