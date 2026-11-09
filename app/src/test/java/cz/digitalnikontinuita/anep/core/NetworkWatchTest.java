package cz.digitalnikontinuita.anep.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class NetworkWatchTest {

    @AfterEach
    void clear() {
        NetworkWatch.forgetUnreachable();
    }

    @Test
    void mlceniServeruJeNoAnswer() {
        assertTrue(NetworkWatch.isNoAnswer(new ConnectException("Connection timed out")));
        assertTrue(NetworkWatch.isNoAnswer(new IOException(new SocketTimeoutException("read"))));
        // ConnectTimeoutException z Apache HttpClient je podtřída InterruptedIOException.
        assertTrue(NetworkWatch.isNoAnswer(new InterruptedIOException("x") { }));
        assertFalse(NetworkWatch.isNoAnswer(new UnknownHostException("x")));
        assertFalse(NetworkWatch.isNoAnswer(new IOException("HTTP 500")));
    }

    @Test
    void rycheSelhaniSeNepamatuje() {
        URI uri = URI.create("http://ocsp.example.test/");
        assertThrows(ConnectException.class, () -> NetworkWatch.watch(uri, "OCSP", () -> {
            throw new ConnectException("Connection refused");
        }));
        assertTrue(NetworkWatch.unreachable().isEmpty());
        assertTrue(NetworkWatch.longestWait(0).isEmpty(), "žádost po skončení neběží");
    }

    @Test
    void druhUrcenyZAdresy() {
        assertEquals("CRL", NetworkWatch.kindOf(URI.create("http://x.test/a/b.CRL")));
        assertEquals("certifikát vydavatele", NetworkWatch.kindOf(URI.create("http://x.test/ca.crt")));
        assertNull(NetworkWatch.kindOf(URI.create("http://x.test/ocsp")));
    }
}
