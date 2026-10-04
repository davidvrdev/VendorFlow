package com.vendorflow.e2e;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vendorflow.shared.profile.ProfileGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.mock.env.MockEnvironment;

class ProfileGuardTest {

    private static final String STRONG_SECRET = "0123456789abcdef0123456789abcdef-prod";

    private static ObjectProvider<JdbcConnectionDetails> jdbc(String url) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (url != null) {
            beans.registerSingleton("details", new JdbcConnectionDetails() {
                @Override
                public String getUsername() {
                    return "u";
                }

                @Override
                public String getPassword() {
                    return "p";
                }

                @Override
                public String getJdbcUrl() {
                    return url;
                }
            });
        }
        return beans.getBeanProvider(JdbcConnectionDetails.class);
    }

    private static MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("app.security.ip-hash-secret", STRONG_SECRET);
        env.setProperty("app.security.session-cookie-secure", "true");
        env.setProperty("app.base-url", "https://app.vendorflow.example");
        env.setProperty("app.email.provider", "resend");
        env.setProperty("vendorflow.billing.allow-disabled-in-prod", "true");
        return env;
    }

    // ---- e2e + prod ----

    @Test
    void e2eTogetherWithProdRefusesToStart() {
        assertThatThrownBy(() -> ProfileGuard.check(List.of("prod", "e2e"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("e2e").hasMessageContaining("prod");
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local", "e2e", "prod");
        assertThatThrownBy(() -> new ProfileGuard(env, jdbc(null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyOtherProfileCombinationIsAllowed() {
        assertThatCode(() -> ProfileGuard.check(List.of("local", "e2e"))).doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.check(List.of("prod"))).doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.check(List.of("test"))).doesNotThrowAnyException();
        assertThatCode(() -> ProfileGuard.check(List.of())).doesNotThrowAnyException();
    }

    // ---- M2: prod must not fail open ----

    @Test
    void prodWithStrongSettingsStarts() {
        assertThatCode(() -> new ProfileGuard(prodEnv(), jdbc("jdbc:postgresql://db.example.com:5432/vf")))
                .doesNotThrowAnyException();
    }

    @Test
    void prodRefusesTheKnownDevIpHashSecret() {
        MockEnvironment env = prodEnv();
        env.setProperty("app.security.ip-hash-secret", "local-dev-only-ip-hash-secret-change-me");
        assertThatThrownBy(() -> new ProfileGuard(env, jdbc(null))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ip-hash-secret").hasMessageNotContaining("local-dev-only");
        assertThatThrownBy(() -> ProfileGuard.checkProd("test-only-ip-hash-secret", true, "https://x.example", "resend"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProfileGuard.checkProd(null, true, "https://x.example", "resend"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodRefusesAShortIpHashSecret() {
        assertThatThrownBy(() -> ProfileGuard.checkProd("x".repeat(31), true, "https://x.example", "resend"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32");
        assertThatCode(() -> ProfileGuard.checkProd("x".repeat(32), true, "https://x.example", "resend"))
                .doesNotThrowAnyException();
    }

    @Test
    void prodRefusesNonSecureCookie() {
        MockEnvironment env = prodEnv();
        env.setProperty("app.security.session-cookie-secure", "false");
        assertThatThrownBy(() -> new ProfileGuard(env, jdbc(null))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-cookie-secure");
    }

    @Test
    void prodRefusesTheLoggingEmailProviderOrNone() {
        MockEnvironment env = prodEnv();
        env.setProperty("app.email.provider", "logging");
        assertThatThrownBy(() -> new ProfileGuard(env, jdbc(null))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.email.provider");
        MockEnvironment missing = new MockEnvironment();
        missing.setActiveProfiles("prod");
        missing.setProperty("app.security.ip-hash-secret", STRONG_SECRET);
        missing.setProperty("app.base-url", "https://app.example.com");
        assertThatThrownBy(() -> new ProfileGuard(missing, jdbc(null))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.email.provider");
        assertThatThrownBy(() -> ProfileGuard.checkProd(STRONG_SECRET, true, "https://a.example", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodRefusesNonHttpsBaseUrl() {
        for (String url : new String[] {"http://app.example.com", "ftp://x", "", "app.example.com"}) {
            assertThatThrownBy(() -> ProfileGuard.checkProd(STRONG_SECRET, true, url, "resend"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("base-url");
        }
        assertThatThrownBy(() -> ProfileGuard.checkProd(STRONG_SECRET, true, null, "resend"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ProfileGuard.checkProd(STRONG_SECRET, true, "HTTPS://app.example.com", "resend"))
                .doesNotThrowAnyException();
    }

    @Test
    void missingCookieSecureSettingDefaultsToSecureSoProdStarts() {
        MockEnvironment fresh = new MockEnvironment();
        fresh.setActiveProfiles("prod");
        fresh.setProperty("app.security.ip-hash-secret", STRONG_SECRET);
        fresh.setProperty("app.base-url", "https://app.example.com");
        fresh.setProperty("app.email.provider", "resend");
        fresh.setProperty("vendorflow.billing.allow-disabled-in-prod", "true");
        assertThatCode(() -> new ProfileGuard(fresh, jdbc(null))).doesNotThrowAnyException();
    }

    // ---- M2: non-prod with a non-local database ----

    @Test
    void nonProdWithRemoteDatabaseRefusesToStartUnlessExplicitlyAllowed() {
        MockEnvironment env = new MockEnvironment(); // no active profile = the "local" default
        assertThatThrownBy(() -> new ProfileGuard(env, jdbc("jdbc:postgresql://db.prod.example.com:5432/vf")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not local")
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");

        MockEnvironment allowed = new MockEnvironment().withProperty("app.allow-non-local-db-without-prod", "true");
        assertThatCode(() -> new ProfileGuard(allowed, jdbc("jdbc:postgresql://db.prod.example.com:5432/vf")))
                .doesNotThrowAnyException();
    }

    @Test
    void localHostsAreAllowedWithoutOptIn() {
        for (String url : new String[] {"jdbc:postgresql://localhost:5432/vendorflow",
                "jdbc:postgresql://127.0.0.1:55432/test?loggerLevel=OFF", "jdbc:postgresql://[::1]:5432/vf",
                "jdbc:postgresql://LOCALHOST/vf", "jdbc:postgresql://host.docker.internal:5432/vf",
                "jdbc:postgresql://user:pw@localhost:5432/vf"}) {
            assertThatCode(() -> ProfileGuard.checkDatabaseHost(url, false)).as(url).doesNotThrowAnyException();
        }
        assertThatCode(() -> new ProfileGuard(new MockEnvironment(), jdbc(null))).doesNotThrowAnyException();
    }

    @Test
    void nonLocalOrTrickyHostsAreRefused() {
        for (String url : new String[] {"jdbc:postgresql://db.example.com/vf",
                "jdbc:postgresql://localhost.evil.example.com:5432/vf", "jdbc:postgresql://evil.example.com@localhost.x/vf",
                "jdbc:postgresql://localhost:5432,db.example.com:5432/vf", "jdbc:postgresql://10.0.0.5:5432/vf",
                "jdbc:postgresql:vf", "garbage", "jdbc:postgresql:///vf"}) {
            assertThatThrownBy(() -> ProfileGuard.checkDatabaseHost(url, false)).as(url)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void prodDoesNotApplyTheLocalDatabaseRule() {
        assertThatCode(() -> new ProfileGuard(prodEnv(), jdbc("jdbc:postgresql://db.supabase.example:5432/postgres")))
                .doesNotThrowAnyException();
    }
}
