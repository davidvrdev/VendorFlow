package com.vendorflow.document.infrastructure.storage;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** In-process stand-in for clamd: speaks INSTREAM, flags the EICAR test string, can be told to misbehave. */
final class FakeClamd implements Closeable {

    enum Mode { NORMAL, HANG, ERROR_REPLY, GARBAGE }

    private final ServerSocket server;
    final AtomicReference<byte[]> received = new AtomicReference<>();
    final AtomicInteger chunks = new AtomicInteger();
    volatile Mode mode = Mode.NORMAL;

    FakeClamd() throws IOException {
        server = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "fake-clamd");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                handle(s);
            } catch (IOException e) {
                // closed or client gone
            }
        }
    }

    private void handle(Socket s) throws IOException {
        DataInputStream in = new DataInputStream(s.getInputStream());
        byte[] command = in.readNBytes(10);
        if (!"zINSTREAM\0".equals(new String(command, StandardCharsets.US_ASCII))) {
            return;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int count = 0;
        int len;
        while ((len = in.readInt()) != 0) {
            body.write(in.readNBytes(len));
            count++;
        }
        received.set(body.toByteArray());
        chunks.set(count);
        if (mode == Mode.HANG) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        String reply;
        if (mode == Mode.ERROR_REPLY) {
            reply = "INSTREAM size limit exceeded. ERROR";
        } else if (mode == Mode.GARBAGE) {
            reply = "what?";
        } else if (new String(body.toByteArray(), StandardCharsets.ISO_8859_1).contains("EICAR-STANDARD")) {
            reply = "stream: Win.Test.EICAR_HDB-1 FOUND";
        } else {
            reply = "stream: OK";
        }
        s.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
