package com.vendorflow.document.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ClamAvFileScanner against an in-process fake clamd (real TCP, real INSTREAM framing). */
class ClamAvFileScannerTest {

    FakeClamd clamd;

    @BeforeEach
    void start() throws Exception {
        clamd = new FakeClamd();
    }

    @AfterEach
    void stop() throws Exception {
        clamd.close();
    }

    ClamAvFileScanner scanner(int port, int readTimeoutMs, int chunk, long max) {
        return new ClamAvFileScanner("127.0.0.1", port, 1000, readTimeoutMs, chunk, max);
    }

    @Test
    void cleanFileIsAcceptedAndStreamedInChunks() {
        byte[] data = new byte[10_000];
        Arrays.fill(data, (byte) 'a');
        FileScanner.Result result = scanner(clamd.port(), 2000, 4096, 1 << 20).scan(new ByteArrayInputStream(data),
                "application/pdf");
        assertThat(result.clean()).isTrue();
        assertThat(clamd.received.get()).isEqualTo(data);
        assertThat(clamd.chunks.get()).isEqualTo(3);
    }

    @Test
    void foundSignatureIsRejectedAndOnlyTheReasonCarriesTheName() {
        byte[] eicar = "%PDF-1.4 EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.ISO_8859_1);
        FileScanner.Result result = scanner(clamd.port(), 2000, 4096, 1 << 20).scan(new ByteArrayInputStream(eicar),
                "application/pdf");
        assertThat(result.clean()).isFalse();
        assertThat(result.reason()).contains("Win.Test.EICAR_HDB-1");
    }

    @Test
    void emptyFileStillGetsAVerdict() {
        assertThat(scanner(clamd.port(), 2000, 4096, 1024).scan(new ByteArrayInputStream(new byte[0]), "x").clean())
                .isTrue();
    }

    @Test
    void readTimeoutFailsClosed() {
        clamd.mode = FakeClamd.Mode.HANG;
        assertThatThrownBy(() -> scanner(clamd.port(), 300, 4096, 1024).scan(new ByteArrayInputStream(new byte[5]), "x"))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void connectionRefusedFailsClosed() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> scanner(closedPort, 300, 4096, 1024).scan(new ByteArrayInputStream(new byte[5]), "x"))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void errorAndGarbageRepliesFailClosed() {
        clamd.mode = FakeClamd.Mode.ERROR_REPLY;
        assertThatThrownBy(() -> scanner(clamd.port(), 2000, 4096, 1024).scan(new ByteArrayInputStream(new byte[5]), "x"))
                .isInstanceOf(ScannerUnavailableException.class);
        clamd.mode = FakeClamd.Mode.GARBAGE;
        assertThatThrownBy(() -> scanner(clamd.port(), 2000, 4096, 1024).scan(new ByteArrayInputStream(new byte[5]), "x"))
                .isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void fileOverTheConfiguredMaxIsRejectedNotSentOnwards() {
        FileScanner.Result result = scanner(clamd.port(), 2000, 4096, 100).scan(new ByteArrayInputStream(new byte[101]),
                "x");
        assertThat(result.clean()).isFalse();
    }
}
