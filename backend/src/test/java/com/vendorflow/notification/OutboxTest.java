package com.vendorflow.notification;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class OutboxTest extends IntegrationTest {

    @Autowired OutboxService outbox;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    TransactionTemplate tx;

    @BeforeEach
    void clean() {
        tx = new TransactionTemplate(txManager);
        // Dispatch claims every due row in the table, so start from an empty outbox.
        jdbc.update("delete from notification");
    }

    private String enqueueVerification(String key) {
        String recipient = "recipient-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        tx.executeWithoutResult(s -> outbox.enqueue(NotificationKind.EMAIL_VERIFICATION, null, recipient, key,
                Map.of("token", "tok-" + key, "fullName", "Ada")));
        return recipient;
    }

    private Map<String, Object> row(String key) {
        return jdbc.queryForMap("select * from notification where idempotency_key = ?", key);
    }

    @Test
    void dispatchSendsMarksSentAndScrubsToken() {
        String recipient = enqueueVerification("k-sent");
        assertThat(row("k-sent").get("payload").toString()).contains("tok-k-sent");

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        assertThat(emailSender.sent()).hasSize(1);
        EmailMessage message = emailSender.sent().get(0);
        assertThat(message.to()).isEqualTo(recipient);
        assertThat(message.idempotencyKey()).isEqualTo("k-sent"); // passed through for provider-side dedupe
        assertThat(message.textBody()).contains("http://localhost:3000/verify-email#token=tok-k-sent");

        Map<String, Object> r = row("k-sent");
        assertThat(r.get("status")).isEqualTo("SENT");
        assertThat(r.get("attempts")).isEqualTo(1);
        assertThat(r.get("sent_at")).isNotNull();
        assertThat(r.get("provider_message_id")).isNotNull();
        assertThat(r.get("last_error")).isNull();
        assertThat(r.get("payload").toString()).doesNotContain("tok-k-sent").doesNotContain("token");
        assertThat(r.get("payload").toString()).contains("fullName"); // non-secret data is kept

        assertThat(dispatcher.dispatchBatch()).isZero(); // nothing left to do
        assertThat(emailSender.sendCalls()).isEqualTo(1);
    }

    @Test
    void failuresBackOffThenDieAfterSixAttempts() {
        enqueueVerification("k-fail");
        emailSender.failWith(new IllegalStateException("provider down"));
        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofHours(2), Duration.ofHours(6)};

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
            Map<String, Object> r = row("k-fail");
            assertThat(r.get("status")).isEqualTo("FAILED");
            assertThat(r.get("attempts")).isEqualTo(attempt);
            assertThat(r.get("last_error").toString()).contains("provider down");
            Instant next = ((java.sql.Timestamp) r.get("next_attempt_at")).toInstant();
            Duration delay = Duration.between(clock.instant(), next);
            assertThat(delay).isBetween(expected[attempt - 1].minusSeconds(10), expected[attempt - 1].plusSeconds(1));
            assertThat(r.get("payload").toString()).contains("tok-k-fail"); // still needed for the retry

            // not due yet: nothing happens
            assertThat(dispatcher.dispatchBatch()).isZero();
            clock.advance(expected[attempt - 1].plusSeconds(5));
        }

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1); // 6th attempt
        Map<String, Object> dead = row("k-fail");
        assertThat(dead.get("status")).isEqualTo("DEAD");
        assertThat(dead.get("attempts")).isEqualTo(6);
        assertThat(dead.get("payload").toString()).doesNotContain("tok-k-fail");

        clock.advance(Duration.ofDays(2));
        assertThat(dispatcher.dispatchBatch()).isZero(); // DEAD rows are never retried
        assertThat(emailSender.sendCalls()).isEqualTo(6);
    }

    @Test
    void failureThenRecoverySendsOnRetry() {
        enqueueVerification("k-recover");
        emailSender.failWith(new IllegalStateException("temporary"));
        dispatcher.dispatchBatch();
        emailSender.failWith(null);
        clock.advance(Duration.ofMinutes(2));
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        Map<String, Object> r = row("k-recover");
        assertThat(r.get("status")).isEqualTo("SENT");
        assertThat(r.get("attempts")).isEqualTo(2);
        assertThat(r.get("last_error")).isNull();
    }

    @Test
    void lastErrorIsSanitizedAndTruncated() {
        enqueueVerification("k-err");
        String leakedToken = "A".repeat(10) + "b".repeat(40);
        emailSender.failWith(new IllegalStateException("rejected link ...#token=" + leakedToken + " " + "x".repeat(2000)));
        dispatcher.dispatchBatch();
        String lastError = (String) row("k-err").get("last_error");
        assertThat(lastError).doesNotContain(leakedToken);
        assertThat(lastError.length()).isLessThanOrEqualTo(500);
    }

    @Test
    void duplicateIdempotencyKeyIsNoOpAndDoesNotPoisonTransaction() {
        Boolean[] results = new Boolean[3];
        tx.executeWithoutResult(s -> {
            results[0] = outbox.enqueue(NotificationKind.EMAIL_VERIFICATION, null, "a@example.com", "k-dup",
                    Map.of("token", "t1"));
            results[1] = outbox.enqueue(NotificationKind.EMAIL_VERIFICATION, null, "b@example.com", "k-dup",
                    Map.of("token", "t2"));
            // The transaction is still usable after the duplicate.
            results[2] = outbox.enqueue(NotificationKind.EMAIL_VERIFICATION, null, "c@example.com", "k-other",
                    Map.of("token", "t3"));
        });
        assertThat(results).containsExactly(true, false, true);
        assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).isEqualTo(2);
        assertThat(row("k-dup").get("recipient_email")).isEqualTo("a@example.com"); // the first one wins
    }

    @Test
    void enqueueRequiresATransaction() {
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.transaction.IllegalTransactionStateException.class,
                () -> outbox.enqueue(NotificationKind.EMAIL_VERIFICATION, null, "a@example.com", "k-notx", Map.of()));
    }

    @Test
    void enqueueRollsBackWithTheCallersTransaction() {
        try {
            tx.executeWithoutResult(s -> {
                outbox.enqueue(NotificationKind.EMAIL_VERIFICATION, null, "a@example.com", "k-rollback", Map.of());
                throw new IllegalStateException("business failure");
            });
        } catch (IllegalStateException expected) {
            // expected
        }
        assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).isZero();
    }

    @Test
    void twoConcurrentDispatchersNeverSendTheSameRowTwice() throws Exception {
        int total = 40; // default batch size is 20, so each dispatcher can claim a full batch
        for (int i = 0; i < total; i++) {
            enqueueVerification("k-conc-" + i);
        }
        emailSender.delay(25);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return dispatcher.dispatchBatch();
            }));
        }
        start.countDown();
        int processed = 0;
        for (Future<Integer> f : futures) {
            processed += f.get();
        }
        pool.shutdown();

        assertThat(processed).isEqualTo(total);
        assertThat(emailSender.sendCalls()).isEqualTo(total);
        Set<String> keys = new HashSet<>();
        emailSender.sent().forEach(m -> keys.add(m.idempotencyKey()));
        assertThat(keys).hasSize(total);
        assertThat(jdbc.queryForObject("select count(*) from notification where status = 'SENT' and attempts = 1",
                Integer.class)).isEqualTo(total);
    }

    @Test
    void signupEmailFlowDeliversLinkAndNeverLogsTheToken() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try {
            TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
            TestAccounts.Account account = accounts.signup(TestAccounts.uniqueEmail(), TestAccounts.PASSWORD,
                    "<script>alert(1)</script>", "Mail Org");
            String rawToken = json.readTree(jdbc.queryForObject(
                    "select payload::text from notification where recipient_email = ?", String.class,
                    account.email())).get("token").asString();
            assertThat(rawToken).hasSize(43);

            dispatcher.dispatchBatch();

            EmailMessage message = emailSender.sent().stream().filter(m -> m.to().equals(account.email()))
                    .findFirst().orElseThrow();
            assertThat(message.textBody()).contains("/verify-email#token=" + rawToken);
            assertThat(message.htmlBody()).contains("/verify-email#token=" + rawToken)
                    .doesNotContain("<script>").contains("&lt;script&gt;");
            assertThat(message.toString()).doesNotContain(rawToken);

            // Nothing logged anywhere (all loggers, all levels) contains the token or the verification link.
            for (ILoggingEvent event : new ArrayList<>(appender.list)) {
                assertThat(event.getFormattedMessage()).doesNotContain(rawToken).doesNotContain("verify-email");
            }
            // And the DB row no longer has it.
            assertThat(jdbc.queryForObject("select payload::text from notification where recipient_email = ?",
                    String.class, account.email())).doesNotContain(rawToken);
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void loggingEmailSenderLogsOnlyIdAndRecipientDomain() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        logger.addAppender(appender);
        try {
            UUID id = UUID.randomUUID();
            new LoggingEmailSender().send(new EmailMessage(id, "key-1", "jane.doe@acme-corp.com", "Secret subject",
                    "link http://x/verify-email#token=SECRETTOKEN", "<a>SECRETTOKEN</a>"));
            assertThat(appender.list).hasSize(1);
            String line = appender.list.get(0).getFormattedMessage();
            assertThat(line).contains(id.toString()).contains("acme-corp.com");
            assertThat(line).doesNotContain("jane.doe").doesNotContain("SECRETTOKEN").doesNotContain("Secret subject");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---- L2: last_error redaction ----

    @Test
    void lastErrorRedactsEmailAddressesAndIsCappedAt500() {
        enqueueVerification("k-mail");
        emailSender.failWith(new IllegalStateException(
                "Recipient victim.person@example.com rejected; cc <other@corp.example.org>"));
        dispatcher.dispatchBatch();
        String lastError = (String) row("k-mail").get("last_error");
        assertThat(lastError).doesNotContain("victim.person").doesNotContain("example.com").doesNotContain("@");
        assertThat(lastError).contains("rejected");

        assertThat(OutboxDispatcher.sanitize(new IllegalStateException("a@b.c " + "z ".repeat(1000)))).hasSize(500);
    }

    // ---- L1: tokens do not linger in undelivered rows ----

    private void enqueueWithKind(NotificationKind kind, String key) {
        tx.executeWithoutResult(s -> outbox.enqueue(kind, null, "scrub-" + key + "@example.com", key,
                Map.of("token", "tok-" + key, "fullName", "Ada")));
    }

    @Test
    void undeliveredAccountRowsOlderThan24hAreMarkedDeadAndLoseTheirToken() {
        enqueueWithKind(NotificationKind.EMAIL_VERIFICATION, "old-pending");
        enqueueWithKind(NotificationKind.PASSWORD_RESET, "old-failed");
        enqueueWithKind(NotificationKind.EMAIL_VERIFICATION, "fresh");
        // old-failed is FAILED and not due; old-pending is PENDING (dispatcher was down)
        jdbc.update("update notification set status = 'FAILED', next_attempt_at = now() + interval '30 days' "
                + "where idempotency_key = 'old-failed'");
        jdbc.update("update notification set created_at = now() - interval '25 hours' "
                + "where idempotency_key in ('old-pending', 'old-failed')");
        jdbc.update("update notification set created_at = now() - interval '23 hours' where idempotency_key = 'fresh'");
        // a SENT row is already scrubbed by the normal path; the scrub must not touch SENT rows
        enqueueWithKind(NotificationKind.EMAIL_VERIFICATION, "old-sent");
        jdbc.update("update notification set status = 'SENT', created_at = now() - interval '30 hours' "
                + "where idempotency_key = 'old-sent'");

        dispatcher.dispatchBatch(); // the scrub runs at the start of every cycle

        for (String key : List.of("old-pending", "old-failed")) {
            Map<String, Object> r = row(key);
            assertThat(r.get("status")).as(key).isEqualTo("DEAD");
            assertThat(r.get("payload").toString()).as(key).doesNotContain("tok-").contains("fullName");
            assertThat(r.get("last_error").toString()).contains("expired");
        }
        assertThat(emailSender.sent()).extracting(EmailMessage::idempotencyKey).containsExactly("fresh");
        assertThat(row("fresh").get("status")).isEqualTo("SENT");
        assertThat(row("old-sent").get("status")).isEqualTo("SENT");
    }

    @Test
    void scrubUsesTheClockAndInvitationsKeepTheirTokenForSevenDays() {
        tx.executeWithoutResult(st -> outbox.enqueue(NotificationKind.INVITATION, null, "scrub-invite@example.com",
                "invite", Map.of("token", "tok-invite", "organizationName", "Acme")));
        enqueueWithKind(NotificationKind.EMAIL_VERIFICATION, "verify");
        emailSender.failWith(new IllegalStateException("down"));
        dispatcher.dispatchBatch(); // both FAILED with backoff, token still there
        assertThat(row("verify").get("payload").toString()).contains("tok-verify");

        clock.advance(Duration.ofHours(25)); // both due again, but "verify" is beyond the account-token lifetime
        emailSender.failWith(null);
        dispatcher.dispatchBatch();
        assertThat(row("verify").get("status")).isEqualTo("DEAD");
        assertThat(row("verify").get("payload").toString()).doesNotContain("tok-verify");
        // 25h is within the invitation token lifetime (7 days): still delivered normally
        assertThat(row("invite").get("status")).isEqualTo("SENT");

        tx.executeWithoutResult(st -> outbox.enqueue(NotificationKind.INVITATION, null, "scrub-invite2@example.com",
                "invite2", Map.of("token", "tok-invite2", "organizationName", "Acme")));
        jdbc.update("update notification set created_at = now() - interval '8 days' where idempotency_key = 'invite2'");
        dispatcher.dispatchBatch();
        assertThat(row("invite2").get("status")).isEqualTo("DEAD");
        assertThat(row("invite2").get("payload").toString()).doesNotContain("tok-invite2");
    }

    // ---- M3: per-recipient throttle of account emails ----

    private boolean enqueueFor(NotificationKind kind, String recipient, String key) {
        return Boolean.TRUE.equals(
                tx.execute(s -> outbox.enqueue(kind, null, recipient, key, Map.of("token", "t-" + key))));
    }

    @Test
    void accountEmailsAreLimitedToThreePerRecipientPerHour() {
        String victim = "Victim-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxService.class);
        logger.addAppender(appender);
        try {
            assertThat(enqueueFor(NotificationKind.EMAIL_VERIFICATION, victim, "th-1")).isTrue();
            assertThat(enqueueFor(NotificationKind.PASSWORD_RESET, victim.toLowerCase(), "th-2")).isTrue();
            assertThat(enqueueFor(NotificationKind.PASSWORD_RESET, victim, "th-3")).isTrue();
            // 4th within the hour: silently not enqueued (case-insensitive recipient, both kinds counted together)
            assertThat(enqueueFor(NotificationKind.EMAIL_VERIFICATION, victim.toUpperCase(), "th-4")).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from notification where idempotency_key = 'th-4'",
                    Integer.class)).isZero();
            // other recipients and non-account kinds are not affected
            assertThat(enqueueFor(NotificationKind.EMAIL_VERIFICATION, "someone-else@example.com", "th-5")).isTrue();
            assertThat(enqueueFor(NotificationKind.INVITATION, victim, "th-6")).isTrue();

            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel().toString()).isEqualTo("INFO");
                assertThat(e.getFormattedMessage()).contains("EMAIL_VERIFICATION").contains("example.com")
                        .doesNotContain(victim.toLowerCase()).doesNotContain("ictim");
            });

            clock.advance(Duration.ofMinutes(61)); // window passed
            assertThat(enqueueFor(NotificationKind.EMAIL_VERIFICATION, victim, "th-7")).isTrue();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void passwordResetApiResponseIsUnchangedWhenThrottled() throws Exception {
        TestAccounts accounts = new TestAccounts(mvc, json, jdbc);
        TestAccounts.Account a = accounts.signup("Throttle Org");
        for (int i = 0; i < 5; i++) {
            accounts.newClient().post("/api/v1/auth/password-reset/request", Map.of("email", a.email()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        }
        // 1 verification (signup) + 2 resets fit in the budget of 3; the rest was dropped
        assertThat(jdbc.queryForObject("select count(*) from notification where recipient_email = ?", Integer.class,
                a.email())).isEqualTo(3);
    }
}
