package com.vendorflow.e2e;

import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.EmailSender;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * E2E-only email "provider": keeps the last {@value #CAPACITY} messages in memory so Playwright can read the
 * verification / reset / invitation links. It exists only under profile {@code e2e}, which must never be combined
 * with {@code prod} (ProfileGuard refuses to start). It deliberately stores link-bearing content, which is why it is
 * unreachable in any other profile.
 */
@Component
@Profile("e2e")
public class InMemoryMailbox implements EmailSender {

    static final int CAPACITY = 200;
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+");

    public record StoredMessage(String kind, String to, String subject, List<String> links, Instant receivedAt) {
    }

    private final Deque<StoredMessage> messages = new ArrayDeque<>();

    @Override
    public synchronized String send(EmailMessage message) {
        List<String> links = URL.matcher(message.textBody()).results().map(java.util.regex.MatchResult::group).toList();
        messages.addLast(new StoredMessage(message.kind() == null ? null : message.kind().name(), message.to(),
                message.subject(), links, Instant.now()));
        while (messages.size() > CAPACITY) {
            messages.removeFirst();
        }
        return "mailbox-" + message.notificationId();
    }

    /** Oldest first, recipient compared case-insensitively. */
    public synchronized List<StoredMessage> messagesFor(String to) {
        String wanted = to.trim().toLowerCase(Locale.ROOT);
        return messages.stream().filter(m -> m.to().toLowerCase(Locale.ROOT).equals(wanted)).toList();
    }
}
