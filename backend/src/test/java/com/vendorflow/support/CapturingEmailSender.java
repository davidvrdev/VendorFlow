package com.vendorflow.support;

import com.vendorflow.notification.EmailMessage;
import com.vendorflow.notification.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double for the email provider: records messages, and can be told to fail or to be slow. */
public class CapturingEmailSender implements EmailSender {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger sendCalls = new AtomicInteger();
    private volatile RuntimeException failure;
    private volatile long delayMillis;

    @Override
    public String send(EmailMessage message) {
        sendCalls.incrementAndGet();
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) {
            throw failure;
        }
        sent.add(message);
        return "test-" + message.notificationId();
    }

    public List<EmailMessage> sent() {
        return List.copyOf(sent);
    }

    public int sendCalls() {
        return sendCalls.get();
    }

    public void failWith(RuntimeException e) {
        this.failure = e;
    }

    public void delay(long millis) {
        this.delayMillis = millis;
    }

    public void reset() {
        sent.clear();
        sendCalls.set(0);
        failure = null;
        delayMillis = 0;
    }
}
