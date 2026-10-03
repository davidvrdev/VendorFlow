package com.vendorflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.EmailSender;
import com.vendorflow.notification.LoggingEmailSender;
import com.vendorflow.notification.NotificationKind;
import com.vendorflow.support.ApiClient;
import com.vendorflow.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Profile e2e: the in-memory mailbox replaces the logging sender and is readable without a session. */
@ActiveProfiles({"test", "e2e"})
class E2eMailboxTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired InMemoryMailbox mailbox;
    @Autowired ApplicationContext context;

    private EmailMessage message(String to, NotificationKind kind, String body) {
        return new EmailMessage(UUID.randomUUID(), "key-" + UUID.randomUUID(), to, "Subject for " + kind, body,
                "<p>html</p>", kind);
    }

    @Test
    void mailboxReplacesTheLoggingSenderAndServesLinksPerRecipientWithoutASession() throws Exception {
        assertThat(context.getBeansOfType(LoggingEmailSender.class)).isEmpty();
        assertThat(context.getBeansOfType(EmailSender.class)).containsKey("inMemoryMailbox");

        String to = "Mailbox-" + UUID.randomUUID() + "@example.com";
        mailbox.send(message(to, NotificationKind.EMAIL_VERIFICATION,
                "Confirm:\nhttp://localhost:3000/verify-email#token=abc_DEF-123\n\nBye"));
        mailbox.send(message(to, NotificationKind.PASSWORD_RESET, "Reset: http://localhost:3000/reset-password#token=zzz"));
        mailbox.send(message("other@example.com", NotificationKind.INVITATION, "http://localhost:3000/invite#token=no"));

        new ApiClient(mvc, json).get("/api/test/mailbox?to=" + to.toLowerCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("EMAIL_VERIFICATION"))
                .andExpect(jsonPath("$[0].links[0]").value("http://localhost:3000/verify-email#token=abc_DEF-123"))
                .andExpect(jsonPath("$[0].subject").value("Subject for EMAIL_VERIFICATION"))
                .andExpect(jsonPath("$[1].kind").value("PASSWORD_RESET"))
                .andExpect(jsonPath("$[1].links[0]").value("http://localhost:3000/reset-password#token=zzz"));
    }

    @Test
    void unknownRecipientGetsAnEmptyListAndMissingParameterIs400() throws Exception {
        new ApiClient(mvc, json).get("/api/test/mailbox?to=nobody@example.com").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        new ApiClient(mvc, json).get("/api/test/mailbox").andExpect(status().isBadRequest());
    }

    @Test
    void mailboxKeepsOnlyTheLastTwoHundredMessages() throws Exception {
        String to = "cap-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 230; i++) {
            mailbox.send(message(to, NotificationKind.INVITATION, "http://localhost:3000/invite#token=" + i));
        }
        assertThat(mailbox.messagesFor(to)).hasSize(200);
        assertThat(mailbox.messagesFor(to).get(0).links().get(0)).endsWith("#token=30");
    }
}
