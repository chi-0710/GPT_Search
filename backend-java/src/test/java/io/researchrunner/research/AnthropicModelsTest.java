package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

/** A3-03 protocol test against a local Messages API stub. */
class AnthropicModelsTest {
    @Test
    void sendsMessagesApiRequestWithApiKeyAndReturnsText() throws Exception {
        var json = new ObjectMapper();
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("synthetic-key", exchange.getRequestHeaders().getFirst("x-api-key"));
            assertTrue(exchange.getRequestHeaders().getFirst("anthropic-version") != null);
            String response = """
                    {"id":"msg_1","type":"message","role":"assistant","model":"claude-3-5-sonnet",
                     "content":[{"type":"text","text":"研究结论"}],
                     "stop_reason":"end_turn","usage":{"input_tokens":7,"output_tokens":3}}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = AnthropicModels.create("claude-3-5-sonnet", 1024, 0.2,
                    "synthetic-key", "http://127.0.0.1:" + server.getAddress().getPort());
            var response = model.call(new Prompt("你好"));
            assertEquals("研究结论", response.getResult().getOutput().getText());
            var body = json.readTree(captured.get());
            assertEquals("claude-3-5-sonnet", body.path("model").asText());
            assertEquals(1024, body.path("max_tokens").asInt());
            assertTrue(body.has("temperature"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void claude4DoesNotSendTemperature() {
        assertTrue(AnthropicModels.suppressesTemperature("claude-sonnet-4-5"));
        assertTrue(AnthropicModels.suppressesTemperature("claude-opus-4-1"));
        assertFalse(AnthropicModels.suppressesTemperature("claude-3-5-sonnet"));
    }
}
