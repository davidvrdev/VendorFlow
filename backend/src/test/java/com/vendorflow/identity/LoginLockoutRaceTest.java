package com.vendorflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.identity.application.LoginAttemptService;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import com.vendorflow.support.TestAccounts;
import com.vendorflow.support.TestAccounts.Account;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** M1: the lock decision is made atomically by the database after bcrypt, not on a stale pre-bcrypt snapshot. */
class LoginLockoutRaceTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoginAttemptService loginAttempts;

    TestAccounts accounts;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(mvc, json, jdbc);
    }

    private int attempts(String userId) {
        return jdbc.queryForObject("select failed_login_attempts from app_user where id = ?::uuid", Integer.class, userId);
    }

    private boolean locked(String userId) {
        return jdbc.queryForObject("select locked_until > now() from app_user where id = ?::uuid",
                Boolean.class, userId);
    }

    @Test
    void twentyParallelWrongGuessesLockTheAccountAndCountNoMoreThanTheThreshold() throws Exception {
        Account account = accounts.signup("Race Org");
        int guesses = 20;
        List<ApiClient> clients = new ArrayList<>();
        for (int i = 0; i < guesses; i++) {
            clients.add(accounts.newClient()); // distinct IPs: the per-IP limiter must not interfere
        }
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (ApiClient client : clients) {
                results.add(pool.submit(() -> {
                    start.await();
                    return client.post("/api/v1/auth/login",
                                    Map.of("email", account.email(), "password", "Wrong-Password-" + UUID.randomUUID()))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get()).isEqualTo(401);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(locked(account.userId())).isTrue();
        // Failures that arrive while the account is locked are not counted: exactly the threshold, never 20.
        assertThat(attempts(account.userId())).isEqualTo(LoginAttemptService.MAX_FAILURES);

        // A correct password after the lock engaged is rejected with the generic body and creates no session.
        ApiClient late = accounts.newClient();
        late.post("/api/v1/auth/login", Map.of("email", account.email(), "password", account.password()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password."));
        assertThat(late.cookie(ApiClient.SESSION_COOKIE)).isNull();
        late.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void successPathIsRefusedByTheDatabaseWhenTheAccountIsLockedEvenWithACorrectPasswordInFlight() {
        // This is exactly what a correct guess that passed bcrypt just before the lock engaged runs into.
        Account account = accountDirect();
        java.util.UUID id = UUID.fromString(account.userId());
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            assertThat(loginAttempts.recordFailure(id)).isTrue();
        }
        assertThat(loginAttempts.recordSuccess(id)).as("locked: success must not be recorded").isFalse();
        assertThat(loginAttempts.recordFailure(id)).as("locked: failure is not counted").isFalse();
        assertThat(attempts(account.userId())).isEqualTo(LoginAttemptService.MAX_FAILURES);
        assertThat(jdbc.queryForObject("select last_login_at is null from app_user where id = ?::uuid", Boolean.class,
                account.userId())).isTrue();

        clock.advance(LoginAttemptService.LOCK_DURATION.plusSeconds(5));
        assertThat(loginAttempts.recordSuccess(id)).isTrue();
        assertThat(attempts(account.userId())).isZero();
    }

    @Test
    void oneFailureAfterAnExpiredLockLocksAgain() {
        Account account = accountDirect();
        UUID id = UUID.fromString(account.userId());
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            loginAttempts.recordFailure(id);
        }
        clock.advance(LoginAttemptService.LOCK_DURATION.plusSeconds(5));
        assertThat(loginAttempts.recordFailure(id)).isTrue(); // counter kept at 5 -> 6th failure re-locks
        assertThat(loginAttempts.recordSuccess(id)).isFalse();
    }

    private Account accountDirect() {
        try {
            return accounts.signup("Lock Direct Org");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
