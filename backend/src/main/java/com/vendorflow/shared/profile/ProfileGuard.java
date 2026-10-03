package com.vendorflow.shared.profile;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup safety net: refuses to start in configurations that fail open.
 *
 * <ol>
 *   <li>{@code e2e} together with {@code prod}: e2e exposes a public mailbox with emailed secret links.</li>
 *   <li>{@code prod} with weak settings: the known dev ip-hash secret, a secret shorter than 32 characters, a
 *       non-Secure session cookie, or a non-https {@code app.base-url} (links in emails).</li>
 *   <li>NOT {@code prod} while the database host is not local: the default profile is {@code local}, so a deployment
 *       that forgets {@code SPRING_PROFILES_ACTIVE=prod} would otherwise run with local settings against a real
 *       database. Opt in explicitly with {@code app.allow-non-local-db-without-prod=true} (tests/staging).</li>
 * </ol>
 */
@Component
public class ProfileGuard {

    static final int MIN_PROD_SECRET_LENGTH = 32;
    /** Secrets shipped in application-local.yml / application-test.yml: never acceptable in prod. */
    static final Set<String> KNOWN_DEV_SECRETS =
            Set.of("local-dev-only-ip-hash-secret-change-me", "test-only-ip-hash-secret");
    static final Set<String> LOCAL_DB_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "host.docker.internal");
    private static final Pattern JDBC_AUTHORITY = Pattern.compile("^jdbc:[A-Za-z0-9]+://([^/?#]*)");

    public ProfileGuard(Environment environment, ObjectProvider<JdbcConnectionDetails> jdbcDetails) {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        check(profiles);
        if (profiles.contains("prod")) {
            checkProd(environment.getProperty("app.security.ip-hash-secret"),
                    environment.getProperty("app.security.session-cookie-secure", Boolean.class, true),
                    environment.getProperty("app.base-url"));
        } else {
            JdbcConnectionDetails details = jdbcDetails.getIfAvailable();
            checkDatabaseHost(details == null ? null : details.getJdbcUrl(),
                    environment.getProperty("app.allow-non-local-db-without-prod", Boolean.class, false));
        }
    }

    /** @throws IllegalStateException if both {@code e2e} and {@code prod} are active */
    public static void check(Collection<String> activeProfiles) {
        if (activeProfiles.contains("e2e") && activeProfiles.contains("prod")) {
            throw new IllegalStateException(
                    "Profiles 'e2e' and 'prod' must never be active together (e2e exposes a public mailbox).");
        }
    }

    /** @throws IllegalStateException when a prod setting is weak (the message never contains the secret itself) */
    public static void checkProd(String ipHashSecret, boolean sessionCookieSecure, String baseUrl) {
        if (ipHashSecret == null || KNOWN_DEV_SECRETS.contains(ipHashSecret)) {
            throw new IllegalStateException(
                    "Profile 'prod': app.security.ip-hash-secret is missing or a known dev value.");
        }
        if (ipHashSecret.length() < MIN_PROD_SECRET_LENGTH) {
            throw new IllegalStateException("Profile 'prod': app.security.ip-hash-secret must be at least "
                    + MIN_PROD_SECRET_LENGTH + " characters.");
        }
        if (!sessionCookieSecure) {
            throw new IllegalStateException("Profile 'prod': app.security.session-cookie-secure must be true.");
        }
        if (baseUrl == null || !baseUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException("Profile 'prod': app.base-url must be an https:// URL.");
        }
    }

    /**
     * Rule for every NON-prod start. A null URL (no datasource at all) is accepted; a URL we cannot parse is treated
     * as non-local.
     *
     * @throws IllegalStateException if the database host is not local and there is no explicit opt-in
     */
    public static void checkDatabaseHost(String jdbcUrl, boolean allowNonLocal) {
        if (jdbcUrl == null || allowNonLocal || allHostsLocal(jdbcUrl)) {
            return;
        }
        throw new IllegalStateException("Profile 'prod' is not active but the database host is not local. A missing "
                + "SPRING_PROFILES_ACTIVE=prod would run production data with local-profile settings. Activate 'prod', "
                + "or set app.allow-non-local-db-without-prod=true if this is intentional (e.g. staging).");
    }

    static boolean allHostsLocal(String jdbcUrl) {
        Matcher m = JDBC_AUTHORITY.matcher(jdbcUrl);
        if (!m.find() || m.group(1).isEmpty()) {
            return false;
        }
        for (String hostPort : m.group(1).split(",")) {
            String host = hostPort.substring(hostPort.lastIndexOf('@') + 1);
            if (host.startsWith("[")) {
                int end = host.indexOf(']');
                host = end < 0 ? host : host.substring(0, end + 1);
            } else if (host.indexOf(':') >= 0) {
                host = host.substring(0, host.indexOf(':'));
            }
            if (!LOCAL_DB_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
