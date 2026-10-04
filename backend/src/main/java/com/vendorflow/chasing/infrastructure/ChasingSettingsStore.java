package com.vendorflow.chasing.infrastructure;

import com.vendorflow.chasing.application.ChasingSettings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** JDBC access to {@code chasing_settings}. Every method takes the organization id; none reads it from request input. */
@Component
public class ChasingSettingsStore {

    private final JdbcClient jdbc;

    public ChasingSettingsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChasingSettings> find(UUID organizationId) {
        return jdbc.sql("""
                select enabled, cadence_days, max_attempts, lead_days, send_hour_local, cc_staff
                from chasing_settings where organization_id = :organizationId""")
                .param("organizationId", organizationId)
                .query((rs, i) -> new ChasingSettings(rs.getBoolean("enabled"), rs.getInt("cadence_days"),
                        rs.getInt("max_attempts"), rs.getInt("lead_days"), rs.getInt("send_hour_local"),
                        rs.getBoolean("cc_staff")))
                .optional();
    }

    public ChasingSettings findOrDefault(UUID organizationId) {
        return find(organizationId).orElse(ChasingSettings.DEFAULTS);
    }

    public void upsert(UUID organizationId, ChasingSettings s, Instant now) {
        jdbc.sql("""
                insert into chasing_settings (organization_id, enabled, cadence_days, max_attempts, lead_days,
                                              send_hour_local, cc_staff, updated_at)
                values (:organizationId, :enabled, :cadence, :max, :lead, :hour, :cc, :now)
                on conflict (organization_id) do update set enabled = excluded.enabled,
                    cadence_days = excluded.cadence_days, max_attempts = excluded.max_attempts,
                    lead_days = excluded.lead_days, send_hour_local = excluded.send_hour_local,
                    cc_staff = excluded.cc_staff, updated_at = excluded.updated_at""")
                .param("organizationId", organizationId).param("enabled", s.enabled())
                .param("cadence", s.cadenceDays()).param("max", s.maxAttempts()).param("lead", s.leadDays())
                .param("hour", s.sendHourLocal()).param("cc", s.ccStaff())
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).update();
    }

    /** What the scheduler needs to decide, without opening a transaction, whether an organization may be due. */
    public record EnabledOrganization(UUID organizationId, String timeZone, int sendHourLocal) {
    }

    /** All organizations with chasing enabled (system job; one small query per tick). */
    public List<EnabledOrganization> findEnabled() {
        return jdbc.sql("""
                select s.organization_id, o.time_zone, s.send_hour_local
                from chasing_settings s join organization o on o.id = s.organization_id
                where s.enabled order by s.organization_id""")
                .query((rs, i) -> new EnabledOrganization(rs.getObject("organization_id", UUID.class),
                        rs.getString("time_zone"), rs.getInt("send_hour_local")))
                .list();
    }
}
