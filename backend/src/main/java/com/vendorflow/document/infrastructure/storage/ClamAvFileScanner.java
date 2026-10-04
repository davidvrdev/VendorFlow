package com.vendorflow.document.infrastructure.storage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Malware scanner talking to a clamd daemon over TCP with the INSTREAM command (ASVS V12.4.2).
 *
 * <p>Protocol: send {@code zINSTREAM\0}, then the file as chunks of {@code [4-byte big-endian length][bytes]}, then a
 * zero length; clamd answers {@code stream: OK}, {@code stream: <signature> FOUND} or {@code ... ERROR}, NUL-terminated.
 * Streaming keeps memory constant. Fail closed: every connection, timeout, protocol or ERROR condition throws
 * {@link ScannerUnavailableException} (the upload is refused with 503), it never returns "clean" by default.
 * The signature name is only placed in {@link Result#reason()} (logs), never sent to the client.
 *
 * <p>clamd must allow at least {@code maxBytes} (StreamMaxLength in clamd.conf, see docs/DEV_SETUP.md).
 */
public final class ClamAvFileScanner implements FileScanner {

    private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_REPLY_BYTES = 1024;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int chunkSize;
    private final long maxBytes;

    public ClamAvFileScanner(String host, int port, int connectTimeoutMs, int readTimeoutMs, int chunkSize,
            long maxBytes) {
        if (chunkSize < 1 || chunkSize > 1 << 20) {
            throw new IllegalArgumentException("chunkSize must be between 1 and 1 MiB");
        }
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.chunkSize = chunkSize;
        this.maxBytes = maxBytes;
    }

    @Override
    public Result scan(InputStream content, String detectedMimeType) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            OutputStream raw = socket.getOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.write(INSTREAM);
            byte[] buffer = new byte[chunkSize];
            long total = 0;
            int read;
            while ((read = content.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxBytes) {
                    // Do not finish the stream: clamd would reject it anyway and an oversize file must not be accepted.
                    return Result.rejected("file exceeds the scan size limit of " + maxBytes + " bytes");
                }
                out.writeInt(read);
                out.write(buffer, 0, read);
            }
            out.writeInt(0);
            out.flush();
            return parse(readReply(socket.getInputStream()));
        } catch (IOException e) {
            // Class name only: connection details/paths stay out of logs and never reach the client.
            throw new ScannerUnavailableException("clamd not usable: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0 && b != '\n') {
            if (reply.size() >= MAX_REPLY_BYTES) {
                throw new IOException("clamd reply too long");
            }
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII).trim();
    }

    private static Result parse(String reply) {
        if (reply.endsWith(" OK")) {
            return Result.ok();
        }
        if (reply.endsWith(" FOUND")) {
            String body = reply.substring(0, reply.length() - " FOUND".length());
            int colon = body.indexOf(':');
            String signature = (colon >= 0 ? body.substring(colon + 1) : body).trim();
            return Result.rejected("malware signature: " + signature);
        }
        // "... ERROR" (e.g. size limit exceeded inside clamd) or an empty/unknown reply: no verdict -> fail closed.
        throw new ScannerUnavailableException("clamd returned no verdict");
    }
}
