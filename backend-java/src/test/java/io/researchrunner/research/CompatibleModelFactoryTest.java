package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import static org.junit.jupiter.api.Assertions.*;

class CompatibleModelFactoryTest {
    @Test
    void providerConfigurationPreservesEndpointsAndNeverFallsBack() {
        var deepseek = CompatibleModelFactory.settings("deepseek", "deepseek-chat", Map.of("DEEPSEEK_API_KEY", "synthetic"));
        assertEquals("https://api.deepseek.com", deepseek.endpoint().toString());
        assertFalse(deepseek.toString().contains("synthetic"));
        var qwen = CompatibleModelFactory.settings("dashscope", "text-embedding-v4", Map.of("DASHSCOPE_API_KEY", "test", "DASHSCOPE_BASE_URL", "http://localhost:1234/compatible-mode/v1"));
        assertEquals("/compatible-mode/v1", qwen.endpoint().getPath());
        assertThrows(IllegalArgumentException.class, () -> CompatibleModelFactory.settings("anthropic", "model", Map.of("OPENAI_API_KEY", "test")));
        assertThrows(IllegalArgumentException.class, () -> CompatibleModelFactory.settings("deepseek", "model", Map.of("OPENAI_API_KEY", "test")));
    }

    @Test
    void actualSpringChatClientPreservesPrefixAuthorizationModelAndUsage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var request = new AtomicReference<JsonNode>();
        var authorization = new AtomicReference<String>();
        var json = new ObjectMapper();
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(json.readTree(exchange.getRequestBody()));
            byte[] body = "{\"id\":\"test\",\"object\":\"chat.completion\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"中文回答\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"total_tokens\":16}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var settings = new CompatibleModelFactory.Settings("deepseek", "deepseek-chat",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1"), "synthetic", Duration.ofSeconds(5));
            var result = new CompatibleModelFactory().chat(settings, 100, 0.0).call(new Prompt("主题"));
            assertEquals("中文回答", result.getResult().getOutput().getText());
            assertEquals(16, result.getMetadata().getUsage().getTotalTokens());
            assertEquals("Bearer synthetic", authorization.get());
            assertEquals("deepseek-chat", request.get().path("model").asText());
            assertEquals(100, request.get().path("max_tokens").asInt());
        } finally { server.stop(0); }
    }

    @Test
    void authenticationFailureDoesNotRetryOrChangeProvider() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            count.incrementAndGet();
            byte[] body = "{\"error\":{\"message\":\"invalid key\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var settings = new CompatibleModelFactory.Settings("deepseek", "deepseek-chat",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "synthetic", Duration.ofSeconds(5));
            assertThrows(Exception.class, () -> new CompatibleModelFactory().chat(settings, 100, 0.0).call(new Prompt("topic")));
            assertEquals(1, count.get());
        } finally { server.stop(0); }
    }
}
