package com.vendorflow.chasing.infrastructure;

import com.vendorflow.compliance.infrastructure.RequirementStatusSql;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JDBC access to {@code vendor_chasing} (state) and {@code vendor_chase} (ledger) plus the one batch query that finds
 * the deficient vendors of an organization. "Deficient" is decided by the shared {@link RequirementStatusSql#REQ_CTE}
 * (never re-implemented here); the {@code :windowDays} bind is the organization's chasing lead time.
 */
@Component
public class ChasingStore {

    /** Requirement statuses that make a vendor worth chasing. REVIEW_REQUIRED is not: the vendor already uploaded. */
    private static final String DEFICIENT = "('MISSING', 'EXPIRED', 'EXPIRING')";

    public record Deficiency(UUID typeId, String name, String status, LocalDate expirationDate) {
    }

    /** A vendor with at least one deficiency, together with its chasing state (defaults when it has no state row). */
    public record Candidate(UUID vendorId, String companyName, String contactName, String email, boolean paused,
            int attempts, LocalDate lastChasedLocalDate, Instant lastChasedAt, UUID lastLinkId, Instant exhaustedNotifiedAt,
            List<Deficiency> deficiencies) {
    }

    public record State(boolean paused, String pausedReason, int attempts, Instant lastChasedAt,
            LocalDate lastChasedLocalDate) {
        static final State NONE = new State(false, null, 0, null, null);
    }

    public record ChaseRow(UUID id, LocalDate localDate, int attempt, JsonNode types, Instant createdAt, UUID linkId,
            String emailStatus) {
    }

    public record ChaseRef(UUID organizationId, UUID vendorId, UUID chaseId, String recipientHash) {
    }

    public enum PauseOutcome {
        CHANGED, UNCHANGED, OPTED_OUT
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ChasingStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static OffsetDateTime utc(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    // ---- scheduler queries (one statement per organization, never per vendor) ----

    /**
     * Ends the episode of vendors that have no deficient requirement any more (or are no longer ACTIVE): attempts and the
     * "exhausted notified" flag reset. {@code last_chased_local_date} is kept so the cadence still spaces episodes.
     */
    public int resetResolvedEpisodes(UUID organizationId, LocalDate today, int leadDays, Instant now) {
        return jdbc.sql("with " + RequirementStatusSql.REQ_CTE + """

                update vendor_chasing s set episode_attempts = 0, exhausted_notified_at = null,
                       updated_at = cast(:now as timestamptz)
                where s.organization_id = :organizationId
                  and (s.episode_attempts > 0 or s.exhausted_notified_at is not null)
                  and not exists (
                    select 1 from req
                    join vendor v on v.organization_id = :organizationId and v.id = req.vendor_id and v.status = 'ACTIVE'
                    where req.vendor_id = s.vendor_id and req.req_status in\s""" + DEFICIENT + ")")
                .param("organizationId", organizationId).param("today", today).param("windowDays", leadDays)
                .param("now", utc(now)).update();
    }

    /** Every ACTIVE vendor of the organization with a MISSING/EXPIRED/EXPIRING requirement, in ONE query. */
    public List<Candidate> findDeficientVendors(UUID organizationId, LocalDate today, int leadDays) {
        return jdbc.sql("with " + RequirementStatusSql.REQ_CTE + """
                ,
                agg as (
                  select vendor_id,
                         jsonb_agg(jsonb_build_object('id', document_type_id, 'name', type_name,
                                                      'status', req_status, 'expirationDate', exp_date)
                                   order by type_sort_order, type_name) as types
                  from req where req_status in\s""" + DEFICIENT + """

                  group by vendor_id
                )
                select v.id, v.company_name, v.contact_name, v.email,
                       coalesce(s.paused, false) as paused, coalesce(s.episode_attempts, 0) as attempts,
                       s.last_chased_local_date, s.last_chased_at, s.last_link_id, s.exhausted_notified_at, agg.types
                from agg
                join vendor v on v.organization_id = :organizationId and v.id = agg.vendor_id and v.status = 'ACTIVE'
                left join vendor_chasing s on s.organization_id = :organizationId and s.vendor_id = v.id
                order by v.id""")
                .param("organizationId", organizationId).param("today", today).param("windowDays", leadDays)
                .query((rs, i) -> {
                    List<Deficiency> types = new ArrayList<>();
                    for (JsonNode t : json.readTree(rs.getString("types"))) {
                        JsonNode exp = t.get("expirationDate");
                        types.add(new Deficiency(UUID.fromString(t.get("id").asString()), t.get("name").asString(),
                                t.get("status").asString(),
                                exp == null || exp.isNull() ? null : LocalDate.parse(exp.asString())));
                    }
                    java.sql.Timestamp notified = rs.getTimestamp("exhausted_notified_at");
                    java.sql.Timestamp lastAt = rs.getTimestamp("last_chased_at");
                    return new Candidate(rs.getObject("id", UUID.class), rs.getString("company_name"),
                            rs.getString("contact_name"), rs.getString("email"), rs.getBoolean("paused"),
                            rs.getInt("attempts"), rs.getObject("last_chased_local_date", LocalDate.class),
                            lastAt == null ? null : lastAt.toInstant(),
                            rs.getObject("last_link_id", UUID.class),
                            notified == null ? null : notified.toInstant(), types);
                }).list();
    }

    /** True when the one vendor has a deficient requirement (status endpoint; single vendor, single query). */
    public boolean hasDeficiency(UUID organizationId, UUID vendorId, LocalDate today, int leadDays) {
        return jdbc.sql("with " + RequirementStatusSql.REQ_CTE + """

                select exists (select 1 from req where req.vendor_id = :vendorId and req.req_status in\s"""
                + DEFICIENT + ")")
                .param("organizationId", organizationId).param("vendorId", vendorId).param("today", today)
                .param("windowDays", leadDays).query(Boolean.class).single();
    }

    // ---- ledger and state writes ----

    /** Chases already written for the organization on this org-local day (the per-organization daily cap counts them). */
    public int countChasesOn(UUID organizationId, LocalDate localDate) {
        return jdbc.sql("select count(*) from vendor_chase where organization_id = :organizationId and local_date = :d")
                .param("organizationId", organizationId).param("d", localDate).query(Integer.class).single();
    }

    /**
     * Re-check right before claiming (L1), ONE statement: creates the state row when missing and locks it (a no-op
     * {@code DO UPDATE} takes the row lock and returns the current value). A concurrent pause/opt-out (an UPDATE of the same
     * row) waits for this transaction and, once it has committed, cancels the email this transaction enqueued; one that
     * committed earlier is seen here. Creating the row first closes the "no row yet" gap.
     *
     * <p>The same statement then takes a {@code FOR SHARE} lock on the vendor row and returns its status and email, so the
     * caller can skip a vendor that was archived or whose address changed since the candidate query.
     *
     * @return the paused flag plus the vendor's current status and email (empty vendor = deleted meanwhile)
     */
    public Lock lockState(UUID organizationId, UUID vendorId, Instant now) {
        return jdbc.sql("""
                with s as (
                  insert into vendor_chasing (vendor_id, organization_id, updated_at) values (:vendorId, :organizationId, :now)
                  on conflict (vendor_id) do update set updated_at = vendor_chasing.updated_at
                  returning paused)
                select s.paused, v.status, v.email from s
                join vendor v on v.organization_id = :organizationId and v.id = :vendorId
                for share of v""")
                .param("vendorId", vendorId).param("organizationId", organizationId).param("now", utc(now))
                .query((rs, i) -> new Lock(rs.getBoolean("paused"), rs.getString("status"), rs.getString("email")))
                .optional().orElse(new Lock(false, null, null));
    }

    public record Lock(boolean paused, String vendorStatus, String vendorEmail) {
        public boolean paused() {
            return paused;
        }
    }

    /** Kept for callers that only need the paused flag. */
    public boolean lockStateIsPaused(UUID organizationId, UUID vendorId, Instant now) {
        return lockState(organizationId, vendorId, now).paused();
    }

    /**
     * Of the given address hashes, those that ANY organization chased since {@code since}: the per-recipient
     * cross-organization cap, one query per run. The race of two organizations chasing the same address at the same moment
     * is closed by the unique index {@code vendor_chase_recipient_day_uq} (one chase per address per UTC day), which
     * {@link #insertChase} hits as a refused claim.
     */
    public java.util.Set<String> recipientsChasedSince(Collection<String> recipientHashes, Instant since) {
        if (recipientHashes.isEmpty()) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(jdbc.sql("""
                select distinct recipient_hash from vendor_chase where recipient_hash in (:h) and created_at > :since""")
                .param("h", recipientHashes).param("since", utc(since)).query(String.class).list());
    }

    /** The idempotency claim: 0 = this vendor was already chased on this org-local day (or the address on this UTC day). */
    public int insertChase(UUID organizationId, UUID chaseId, UUID vendorId, LocalDate localDate, int attempt,
            String typesJson, UUID linkId, String optOutHash, Instant optOutExpiresAt, String recipientHash, Instant now) {
        return jdbc.sql("""
                insert into vendor_chase (id, organization_id, vendor_id, local_date, attempt, types, link_id,
                                          opt_out_token_hash, opt_out_expires_at, recipient_hash, created_at)
                values (:id, :organizationId, :vendorId, :localDate, :attempt, cast(:types as jsonb), :linkId,
                        :hash, :expires, :recipientHash, :now)
                on conflict do nothing""")
                .param("id", chaseId).param("organizationId", organizationId).param("vendorId", vendorId)
                .param("localDate", localDate).param("attempt", attempt).param("types", typesJson)
                .param("linkId", linkId).param("hash", optOutHash).param("recipientHash", recipientHash).param("expires", utc(optOutExpiresAt))
                .param("now", utc(now)).update();
    }

    /** Counts the chase in the episode and remembers the link to revoke at the next one. */
    public void recordChase(UUID organizationId, UUID vendorId, LocalDate localDate, UUID linkId, Instant now) {
        jdbc.sql("""
                insert into vendor_chasing (vendor_id, organization_id, episode_attempts, last_chased_at,
                                            last_chased_local_date, last_link_id, updated_at)
                values (:vendorId, :organizationId, 1, :now, :localDate, :linkId, :now)
                on conflict (vendor_id) do update set episode_attempts = vendor_chasing.episode_attempts + 1,
                    last_chased_at = excluded.last_chased_at, last_chased_local_date = excluded.last_chased_local_date,
                    last_link_id = excluded.last_link_id, updated_at = excluded.updated_at""")
                .param("vendorId", vendorId).param("organizationId", organizationId).param("localDate", localDate)
                .param("linkId", linkId).param("now", utc(now)).update();
    }

    public void markExhaustedNotified(UUID organizationId, Collection<UUID> vendorIds, Instant now) {
        if (vendorIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                update vendor_chasing set exhausted_notified_at = :now, updated_at = :now
                where organization_id = :organizationId and vendor_id in (:ids)""")
                .param("organizationId", organizationId).param("ids", vendorIds).param("now", utc(now)).update();
    }

    // ---- staff pause/resume and public opt-out ----

    public State findState(UUID organizationId, UUID vendorId) {
        return jdbc.sql("""
                select paused, paused_reason, episode_attempts, last_chased_at, last_chased_local_date
                from vendor_chasing where organization_id = :organizationId and vendor_id = :vendorId""")
                .param("organizationId", organizationId).param("vendorId", vendorId)
                .query((rs, i) -> {
                    java.sql.Timestamp last = rs.getTimestamp("last_chased_at");
                    return new State(rs.getBoolean("paused"), rs.getString("paused_reason"),
                            rs.getInt("episode_attempts"), last == null ? null : last.toInstant(),
                            rs.getObject("last_chased_local_date", LocalDate.class));
                }).optional().orElse(State.NONE);
    }

    /** Pause as staff: a row that is already paused (by staff or by the vendor) is left alone. */
    public PauseOutcome pause(UUID organizationId, UUID vendorId, Instant now) {
        int changed = jdbc.sql("""
                insert into vendor_chasing (vendor_id, organization_id, paused, paused_reason, paused_at, updated_at)
                values (:vendorId, :organizationId, true, 'MANUAL', :now, :now)
                on conflict (vendor_id) do update set paused = true, paused_reason = 'MANUAL', paused_at = :now,
                    updated_at = :now
                where vendor_chasing.paused = false""")
                .param("vendorId", vendorId).param("organizationId", organizationId).param("now", utc(now)).update();
        return changed == 1 ? PauseOutcome.CHANGED : PauseOutcome.UNCHANGED;
    }

    /** Resume as staff; a vendor's own opt-out cannot be undone from here. Locks the row against a concurrent opt-out. */
    public PauseOutcome resume(UUID organizationId, UUID vendorId, Instant now) {
        Optional<String> reason = jdbc.sql("""
                select coalesce(paused_reason, '') from vendor_chasing
                where organization_id = :organizationId and vendor_id = :vendorId for update""")
                .param("organizationId", organizationId).param("vendorId", vendorId).query(String.class).optional();
        if (reason.isEmpty() || reason.get().isEmpty()) {
            return PauseOutcome.UNCHANGED;
        }
        if (reason.get().equals("OPT_OUT")) {
            return PauseOutcome.OPTED_OUT;
        }
        jdbc.sql("""
                update vendor_chasing set paused = false, paused_reason = null, paused_at = null, updated_at = :now
                where organization_id = :organizationId and vendor_id = :vendorId""")
                .param("organizationId", organizationId).param("vendorId", vendorId).param("now", utc(now)).update();
        return PauseOutcome.CHANGED;
    }

    /** The vendor unsubscribed: true only on the transition (a repeat changes nothing, so it audits/notifies nothing). */
    public boolean optOut(UUID organizationId, UUID vendorId, Instant now) {
        return jdbc.sql("""
                insert into vendor_chasing (vendor_id, organization_id, paused, paused_reason, paused_at, updated_at)
                values (:vendorId, :organizationId, true, 'OPT_OUT', :now, :now)
                on conflict (vendor_id) do update set paused = true, paused_reason = 'OPT_OUT', paused_at = :now,
                    updated_at = :now
                where vendor_chasing.paused_reason is distinct from 'OPT_OUT'""")
                .param("vendorId", vendorId).param("organizationId", organizationId).param("now", utc(now))
                .update() == 1;
    }

    /** Dispatcher backstop: a vendor with a state row that is paused (staff or opt-out). */
    public boolean isPaused(UUID organizationId, UUID vendorId) {
        return jdbc.sql("select count(*) from vendor_chasing where organization_id = :organizationId and vendor_id = :vendorId and paused")
                .param("organizationId", organizationId).param("vendorId", vendorId).query(Integer.class).single() > 0;
    }

    public boolean isOptedOut(UUID organizationId, UUID vendorId) {
        return jdbc.sql("""
                select count(*) from vendor_chasing
                where organization_id = :organizationId and vendor_id = :vendorId and paused_reason = 'OPT_OUT'""")
                .param("organizationId", organizationId).param("vendorId", vendorId).query(Integer.class).single() > 0;
    }

    /** Public lookup: the unguessable opt-out token (by its SHA-256) is the capability and determines the tenant. */
    public Optional<ChaseRef> findChaseByOptOutHash(String hash, Instant now) {
        return jdbc.sql("""
                select organization_id, vendor_id, id, recipient_hash from vendor_chase
                where opt_out_token_hash = :hash and opt_out_expires_at > :now""")
                .param("hash", hash).param("now", utc(now))
                .query((rs, i) -> new ChaseRef(rs.getObject("organization_id", UUID.class),
                        rs.getObject("vendor_id", UUID.class), rs.getObject("id", UUID.class), rs.getString("recipient_hash")))
                .optional();
    }

    // ---- activity list ----

    public long countChases(UUID organizationId, UUID vendorId) {
        return jdbc.sql("select count(*) from vendor_chase where organization_id = :organizationId and vendor_id = :vendorId")
                .param("organizationId", organizationId).param("vendorId", vendorId).query(Long.class).single();
    }

    /** A page of the activity, newest first; the email status is the outbox row of the chase (joined, no N+1). */
    public List<ChaseRow> findChases(UUID organizationId, UUID vendorId, int limit, long offset) {
        return jdbc.sql("""
                select c.id, c.local_date, c.attempt, c.types, c.created_at, c.link_id, n.status as email_status
                from vendor_chase c
                left join notification n on n.organization_id = c.organization_id
                     and n.idempotency_key = 'chase:' || c.vendor_id || ':' || c.local_date
                where c.organization_id = :organizationId and c.vendor_id = :vendorId
                order by c.created_at desc, c.id desc
                limit :limit offset :offset""")
                .param("organizationId", organizationId).param("vendorId", vendorId).param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> new ChaseRow(rs.getObject("id", UUID.class), rs.getObject("local_date", LocalDate.class),
                        rs.getInt("attempt"), json.readTree(rs.getString("types")),
                        rs.getTimestamp("created_at").toInstant(), rs.getObject("link_id", UUID.class),
                        rs.getString("email_status")))
                .list();
    }
}
