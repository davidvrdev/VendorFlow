package com.vendorflow.e2e;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Profile {@code e2e} only (bean does not exist otherwise). Read-only; see InMemoryMailbox. */
@RestController
@RequestMapping("/api/test/mailbox")
@Profile("e2e")
public class MailboxController {

    private final InMemoryMailbox mailbox;

    public MailboxController(InMemoryMailbox mailbox) {
        this.mailbox = mailbox;
    }

    @GetMapping
    public List<InMemoryMailbox.StoredMessage> messages(@RequestParam("to") String to) {
        return mailbox.messagesFor(to);
    }
}
