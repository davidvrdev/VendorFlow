package com.vendorflow.support;

import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.notification.OutboxDispatcher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the emailed link the way a user (or the e2e mailbox) would: run the outbox, find the mail, take the token. */
public final class EmailTokens {

    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

    private EmailTokens() {
    }

    /** Delivers everything that is due, then returns the newest message of this kind for the recipient. */
    public static EmailMessage latest(OutboxDispatcher dispatcher, CapturingEmailSender sender, String to,
            NotificationKind kind) {
        while (dispatcher.dispatchBatch() > 0) {
            // keep going until the outbox is drained
        }
        return sender.sent().stream()
                .filter(m -> m.to().equalsIgnoreCase(to) && m.kind() == kind)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No " + kind + " email for " + to));
    }

    public static long count(OutboxDispatcher dispatcher, CapturingEmailSender sender, String to,
            NotificationKind kind) {
        while (dispatcher.dispatchBatch() > 0) {
            // drain
        }
        return sender.sent().stream().filter(m -> m.to().equalsIgnoreCase(to) && m.kind() == kind).count();
    }

    public static String token(OutboxDispatcher dispatcher, CapturingEmailSender sender, String to,
            NotificationKind kind) {
        return tokenOf(latest(dispatcher, sender, to, kind));
    }

    public static String tokenOf(EmailMessage message) {
        Matcher matcher = TOKEN.matcher(message.textBody());
        if (!matcher.find()) {
            throw new AssertionError("No token link in email");
        }
        return matcher.group(1);
    }
}
