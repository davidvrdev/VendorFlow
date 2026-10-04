package com.vendorflow.shared.profile;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
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
 *       non-Secure session cookie, a non-https {@code app.base-url} (links in emails), or {@code app.email.provider}
 *       other than {@code resend} (the logging sender delivers nothing).</li>
 *   <li>{@code prod} with billing enabled but a Stripe key, webhook secret or price id missing (checkBilling).</li>
 *   <li>{@code prod} with the no-op malware scanner without the explicit opt-in (checkScanner).</li>
 *   <li>{@code prod} without S3 storage (or the explicit filesystem opt-in), see checkStorage.</li>
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
                    environment.getProperty("app.base-url"),
                    environment.getProperty("app.email.provider", "logging"));
            checkBilling(environment.getProperty("vendorflow.billing.enabled", Boolean.class, false),
                    environment.getProperty("vendorflow.billing.allow-disabled-in-prod", Boolean.class, false),
                    environment.getProperty("vendorflow.billing.allow-test-keys-in-prod", Boolean.class, false),
                    environment.getProperty("vendorflow.billing.stripe.secret-key"),
                    environment.getProperty("vendorflow.billing.stripe.webhook-secret"),
                    environment.getProperty("vendorflow.billing.stripe.price-id"));
            checkScanner(environment.getProperty("vendorflow.storage.scanner", "noop"),
                    environment.getProperty("vendorflow.storage.allow-noop-scanner-in-prod", Boolean.class, false));
            checkStorage(environment.getProperty("app.storage.provider", "filesystem"),
                    environment.getProperty("app.storage.allow-filesystem-in-prod", Boolean.class, false),
                    environment.getProperty("app.storage.s3.endpoint"),
                    environment.getProperty("app.storage.s3.region"),
                    environment.getProperty("app.storage.s3.bucket"),
                    environment.getProperty("app.storage.s3.access-key-id"),
                    environment.getProperty("app.storage.s3.secret-access-key"));
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
    public static void checkProd(String ipHashSecret, boolean sessionCookieSecure, String baseUrl,
            String emailProvider) {
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
        if (emailProvider == null || !emailProvider.equalsIgnoreCase("resend")) {
            throw new IllegalStateException("Profile 'prod': app.email.provider must be 'resend' (a production app that "
                    + "silently does not send email is a bug). Set EMAIL_PROVIDER=resend, RESEND_API_KEY and EMAIL_FROM.");
        }
    }

    /**
     * Prod billing rules (messages name variables/properties, never values):
     * disabled billing means no read-only enforcement, so it needs the explicit opt-in
     * {@code vendorflow.billing.allow-disabled-in-prod=true} (then a loud WARN). Enabled billing needs all three Stripe
     * settings, and live-looking keys unless {@code vendorflow.billing.allow-test-keys-in-prod=true}.
     */
    public static void checkBilling(boolean enabled, boolean allowDisabledInProd, boolean allowTestKeysInProd,
            String secretKey, String webhookSecret, String priceId) {
        if (!enabled) {
            if (!allowDisabledInProd) {
                throw new IllegalStateException("Profile 'prod': billing is disabled (BILLING_ENABLED=false), which "
                        + "turns off subscription enforcement. Set BILLING_ENABLED=true, or opt in explicitly with "
                        + "vendorflow.billing.allow-disabled-in-prod=true.");
            }
            LoggerFactory.getLogger(ProfileGuard.class).warn("BILLING IS DISABLED IN PROD "
                    + "(vendorflow.billing.allow-disabled-in-prod=true): no subscription enforcement is applied.");
            return;
        }
        if (isBlank(secretKey) || isBlank(webhookSecret) || isBlank(priceId)) {
            throw new IllegalStateException("Profile 'prod': billing is enabled but STRIPE_SECRET_KEY, "
                    + "STRIPE_WEBHOOK_SECRET or STRIPE_PRICE_ID is missing. Provide them, or set BILLING_ENABLED=false "
                    + "together with vendorflow.billing.allow-disabled-in-prod=true.");
        }
        if (!allowTestKeysInProd) {
            if (!(secretKey.startsWith("sk_live_") || secretKey.startsWith("rk_live_"))) {
                throw new IllegalStateException("Profile 'prod': STRIPE_SECRET_KEY must be a live key (sk_live_ or "
                        + "rk_live_ prefix). Opt in to test keys with vendorflow.billing.allow-test-keys-in-prod=true.");
            }
            if (!webhookSecret.startsWith("whsec_")) {
                throw new IllegalStateException("Profile 'prod': STRIPE_WEBHOOK_SECRET must start with whsec_.");
            }
        }
    }

    /**
     * Prod must scan uploads (ASVS V12.4.2): the no-op scanner needs the explicit opt-in
     * {@code vendorflow.storage.allow-noop-scanner-in-prod=true}.
     */
    public static void checkScanner(String scanner, boolean allowNoopInProd) {
        if (!"clamav".equalsIgnoreCase(scanner) && !allowNoopInProd) {
            throw new IllegalStateException("Profile 'prod': vendorflow.storage.scanner must be 'clamav' (uploads would "
                    + "otherwise not be scanned for malware). Opt out explicitly with "
                    + "vendorflow.storage.allow-noop-scanner-in-prod=true.");
        }
    }

    /**
     * Prod must keep documents off the container's ephemeral disk (they would vanish on every deploy): provider
     * {@code s3} with all five settings, or the explicit opt-in {@code app.storage.allow-filesystem-in-prod=true}
     * (single host with a persistent volume). Messages name variables, never values.
     */
    public static void checkStorage(String provider, boolean allowFilesystemInProd, String endpoint, String region,
            String bucket, String accessKeyId, String secretAccessKey) {
        if ("s3".equalsIgnoreCase(provider)) {
            if (isBlank(endpoint) || isBlank(region) || isBlank(bucket) || isBlank(accessKeyId)
                    || isBlank(secretAccessKey)) {
                throw new IllegalStateException("Profile 'prod': STORAGE_PROVIDER=s3 needs STORAGE_S3_ENDPOINT, "
                        + "STORAGE_S3_REGION, STORAGE_S3_BUCKET, STORAGE_S3_ACCESS_KEY_ID and "
                        + "STORAGE_S3_SECRET_ACCESS_KEY.");
            }
            if (!endpoint.toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new IllegalStateException("Profile 'prod': STORAGE_S3_ENDPOINT must be an https:// URL.");
            }
            return;
        }
        if (!allowFilesystemInProd) {
            throw new IllegalStateException("Profile 'prod': app.storage.provider must be 's3' (the container disk is "
                    + "ephemeral and documents would be lost on deploy). Opt in explicitly with "
                    + "STORAGE_ALLOW_FILESYSTEM_IN_PROD=true only if a persistent volume is mounted.");
        }
        LoggerFactory.getLogger(ProfileGuard.class).warn("FILESYSTEM STORAGE IN PROD (STORAGE_ALLOW_FILESYSTEM_IN_PROD="
                + "true): make sure STORAGE_FILESYSTEM_ROOT is a persistent, backed-up volume.");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
