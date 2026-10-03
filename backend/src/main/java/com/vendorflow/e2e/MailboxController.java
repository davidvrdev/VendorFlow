package com.vendorflow.e2e;

import com.vendorflow.shared.error.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profile {@code e2e} only (bean does not exist otherwise). Read-only; see InMemoryMailbox. Defense in depth: it also
 * answers 404 to any caller whose address is not loopback, so a mis-deployed e2e profile does not expose emailed
 * links to the network. (Behind a trusted proxy the address is the real client IP, see RemoteIpValve.)
 */
@RestController
@RequestMapping("/api/test/mailbox")
@Profile("e2e")
public class MailboxController {

    private final InMemoryMailbox mailbox;

    public MailboxController(InMemoryMailbox mailbox) {
        this.mailbox = mailbox;
    }

    @GetMapping
    public List<InMemoryMailbox.StoredMessage> messages(@RequestParam("to") String to, HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            throw new NotFoundException("Not found");
        }
        return mailbox.messagesFor(to);
    }

    private static boolean isLoopback(String remoteAddr) {
        try {
            return remoteAddr != null && InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
