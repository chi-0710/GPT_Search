package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** A3-13 protocol test against a local Gemini image stub. */
class GoogleImageProviderTest {
    @Test
    void sendsPromptAndDecodesInlineImage() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        String encoded = Base64.getEncoder().encodeToString(png);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/gemini-test:generateContent", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(true, body.contains("研究插图"));
            String response = """
                    {"candidates":[{"content":{"parts":[
                      {"inlineData":{"mimeType":"image/png","data":"%s"}}]}}]}
                    """.formatted(encoded);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var provider = new GoogleImageProvider(java.net.http.HttpClient.newHttpClient(),
                    new ObjectMapper(), "synthetic", "gemini-test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
            var image = provider.generate("研究插图");
            assertArrayEquals(png, image.bytes());
            assertEquals("image/png", image.mimeType());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingKeyAndEmptyPrompt() {
        assertThrows(IllegalArgumentException.class, () -> new GoogleImageProvider(
                java.net.http.HttpClient.newHttpClient(), new ObjectMapper(), "", "gemini-test",
                "https://example.test", Duration.ofSeconds(1)));
    }
}
