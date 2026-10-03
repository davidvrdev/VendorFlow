package com.vendorflow.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default sender until the Resend implementation (Phase 6): delivers nothing. Logs ONLY the notification id and the
 * recipient's domain; never the subject, body, links, tokens or the full address (docs/SECURITY.md section 6).
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public String send(EmailMessage message) {
        String to = message.to();
        String domain = to.contains("@") ? to.substring(to.lastIndexOf('@') + 1) : "unknown";
        log.info("Email not delivered (logging sender): notificationId={} recipientDomain={}",
                message.notificationId(), domain);
        return "logged-" + message.notificationId();
    }
}
