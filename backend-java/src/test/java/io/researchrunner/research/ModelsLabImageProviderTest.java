package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A3-13 protocol test for the ModelsLab polling adapter. */
class ModelsLabImageProviderTest {
    @Test
    void returnsImageFromSuccessfulPoll() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/text2img", exchange -> {
            String body = """
                    {"status":"success","output":["http://127.0.0.1:%d/image.png"]}
                    """.formatted(server.getAddress().getPort());
            respond(exchange, body.getBytes(StandardCharsets.UTF_8), "application/json");
        });
        server.createContext("/image.png", exchange -> respond(exchange, png, "image/png"));
        server.start();
        try {
            var provider = new ModelsLabImageProvider(java.net.http.HttpClient.newHttpClient(),
                    new ObjectMapper(), "synthetic",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/text2img",
                    Duration.ofSeconds(5), true, 1);
            assertArrayEquals(png, provider.generate("研究插图").bytes());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesKey() {
        assertThrows(IllegalArgumentException.class, () -> new ModelsLabImageProvider(
                java.net.http.HttpClient.newHttpClient(), new ObjectMapper(), "", "https://example.test",
                Duration.ofSeconds(1), false));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] bytes, String type)
            throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
