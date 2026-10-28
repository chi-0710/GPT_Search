package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A2-03 SSRF and constructor guards for remote document URLs. */
class RemoteDocumentsTest {
    @Test
    void rejectsLoopbackPrivateLinkLocalAndNonHttpTargets() {
        assertFalse(RemoteDocuments.isPublicHttp(URI.create("http://127.0.0.1/a.pdf")));
        assertFalse(RemoteDocuments.isPublicHttp(URI.create("http://localhost/a.pdf")));
        assertFalse(RemoteDocuments.isPublicHttp(URI.create("http://10.1.2.3/a.pdf")));
        assertFalse(RemoteDocuments.isPublicHttp(URI.create("http://192.168.1.5/a.pdf")));
        assertFalse(RemoteDocuments.isPublicHttp(URI.create("http://169.254.169.254/latest/meta-data")));
        assertFalse(RemoteDocuments.isPublicHttp(URI.create("ftp://example.com/a.pdf")));
        assertFalse(RemoteDocuments.isPublicHttp(URI.create("https:///missing-host.pdf")));
    }

    @Test
    void acceptsPublicIpLiteralAndHttps() {
        assertTrue(RemoteDocuments.isPublicHttp(URI.create("https://8.8.8.8/a.pdf")));
        assertTrue(RemoteDocuments.isPublicHttp(URI.create("http://1.1.1.1/a.txt")));
    }

    @Test
    void validatesLimits() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteDocuments(Duration.ZERO, 1024));
        assertThrows(IllegalArgumentException.class, () -> new RemoteDocuments(Duration.ofSeconds(1), 0));
    }
}
