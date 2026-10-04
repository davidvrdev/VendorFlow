package com.vendorflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.EmailTokens;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * The raw portal token must exist only in the creation response and the vendor's e-mail: not in the database, the
 * audit trail, the outbox after delivery, or ANY log line at the shipped log levels (all loggers, including framework logs of 404s,
 * validation failures, rejected uploads and rate limiting).
 */
class PortalTokenLeakTest extends PortalTestBase {

    @Autowired OutboxDispatcher dispatcher;

    @Test
    void theRawTokenIsNeverStoredAuditedOrLogged() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level originalLevel = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        // Our own code at its most verbose. Spring framework DEBUG logging (FilterChainProxy, DispatcherServlet) prints
        // the request line by design and is off by default; SECURITY.md says never to enable it in production.
        Logger ours = (Logger) LoggerFactory.getLogger("com.vendorflow");
        Level oursOriginal = ours.getLevel();
        ours.setLevel(Level.DEBUG);
        String token;
        String linkId;
        String vendor;
        try {
            vendor = createVendor(member, "Acme", "vendor@example.com");
            String w9 = typeId(member, "W9");
            JsonNode created = createLink(member, vendor, List.of(w9, typeId(member, "COI")), "sendEmail", true);
            token = tokenOf(created);
            linkId = created.get("link").get("id").asString();

            portalGet(token).andExpect(status().isOk());
            portalUpload(token, file("a.pdf", PDF), fields(w9, null, null)).andExpect(status().isCreated());
            portalUpload(token, file("a.exe", PDF), fields(w9, null, null)).andExpect(status().isUnsupportedMediaType());
            portalUpload(token, null, fields(w9, null, null)).andExpect(status().isBadRequest());
            portalUpload(token, file("a.pdf", PDF), fields("not-a-uuid", null, null)).andExpect(status().isBadRequest());
            // routes outside the contract, wrong methods, bad tokens
            ApiClient anon = new ApiClient(mvc, json).primeCsrf().header("X-Portal-Token", token);
            anon.perform(HttpMethod.DELETE, PORTAL, null, true);
            anon.perform(HttpMethod.GET, PORTAL + "/nope", null, true);
            anon.perform(HttpMethod.GET, PORTAL + "/%2e%2e/x", null, true);
            portalGet(token.substring(0, 42) + "!").andExpect(status().isNotFound());
            // staff side: revoke, then the dead link
            member.client().post(linksPath(vendor) + "/" + linkId + "/revoke", null).andExpect(status().isOk());
            portalGet(token).andExpect(status().isNotFound());
            portalUpload(token, file("b.pdf", PDF), fields(w9, null, null)).andExpect(status().isNotFound());
            EmailTokens.latest(dispatcher, emailSender, "vendor@example.com", NotificationKind.DOCUMENT_REQUEST);
        } finally {
            root.detachAppender(appender);
            ours.setLevel(oursOriginal);
            root.setLevel(originalLevel);
        }

        List<String> leaks = new ArrayList<>();
        for (ILoggingEvent e : appender.list) {
            StringBuilder text = new StringBuilder(e.getFormattedMessage());
            e.getMDCPropertyMap().values().forEach(v -> text.append(' ').append(v));
            IThrowableProxy t = e.getThrowableProxy();
            if (t != null) {
                text.append(ThrowableProxyUtil.asString(t));
            }
            if (text.toString().contains(token)) {
                leaks.add(e.getLoggerName() + " [" + e.getLevel() + "]: " + e.getFormattedMessage());
            }
        }
        assertThat(appender.list).as("log capture must have seen the requests").isNotEmpty();
        assertThat(leaks).as("log lines containing the raw portal token").isEmpty();

        // database: no column holds the raw token
        assertThat(count("select count(*) from vendor_upload_link where token_hash = ?", token)).isZero();
        assertThat(count("select count(*) from audit_event where organization_id = ?::uuid and metadata::text like ?",
                orgId, "%" + token + "%")).isZero();
        // outbox: after delivery the secret key is gone from the payload (and the rest never held it)
        assertThat(count("select count(*) from notification where organization_id = ?::uuid and payload::text like ?",
                orgId, "%" + token + "%")).isZero();
        assertThat(count("select count(*) from notification where organization_id = ?::uuid and payload -> 'token' is not null",
                orgId)).isZero();
        // last_error columns neither
        assertThat(count("select count(*) from notification where organization_id = ?::uuid and last_error like ?",
                orgId, "%" + token + "%")).isZero();
    }
}
