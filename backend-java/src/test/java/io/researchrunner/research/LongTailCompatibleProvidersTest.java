package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;

/** A3-08: OpenAI-compatible long-tail providers added on top of the shared adapter. */
class LongTailCompatibleProvidersTest {
    private record Case(String provider, String keyVariable, String baseVariable, String pathPrefix) { }

    private static final List<Case> CASES = List.of(
            new Case("fireworks", "FIREWORKS_API_KEY", "FIREWORKS_BASE_URL", "/fireworks/v1"),
            new Case("together", "TOGETHER_API_KEY", "TOGETHER_BASE_URL", "/together/v1"),
            new Case("litellm", "LITELLM_API_KEY", "LITELLM_BASE_URL", "/litellm/v1"),
            new Case("netmind", "NETMIND_API_KEY", "NETMIND_BASE_URL", "/netmind/v1"),
            new Case("huggingface", "HUGGINGFACE_API_KEY", "HUGGINGFACE_BASE_URL", "/hf/v1"));

    @Test
    void defaultEndpointsOverridesAndRequiredConfigurationAreExplicit() {
        assertEquals("https://api.fireworks.ai/inference/v1",
                CompatibleModelFactory.settings("fireworks", "m", Map.of("FIREWORKS_API_KEY", "k"))
                        .endpoint().toString());
        assertEquals("https://api.together.xyz/v1",
                CompatibleModelFactory.settings("together", "m", Map.of("TOGETHER_API_KEY", "k"))
                        .endpoint().toString());
        assertEquals("https://api.netmind.ai/inference-api/openai/v1",
                CompatibleModelFactory.settings("netmind", "m", Map.of("NETMIND_API_KEY", "k"))
                        .endpoint().toString());
        assertEquals("https://router.huggingface.co/v1",
                CompatibleModelFactory.settings("huggingface", "m", Map.of("HUGGINGFACE_API_KEY", "k"))
                        .endpoint().toString());
        assertEquals("http://localhost:4000/v1",
                CompatibleModelFactory.settings("litellm", "m", Map.of("LITELLM_API_KEY", "k",
                        "LITELLM_BASE_URL", "http://localhost:4000/v1")).endpoint().toString());
        assertThrows(IllegalArgumentException.class,
                () -> CompatibleModelFactory.settings("litellm", "m", Map.of("LITELLM_API_KEY", "k")));
        assertThrows(IllegalArgumentException.class,
                () -> CompatibleModelFactory.settings("fireworks", "m", Map.of("OPENAI_API_KEY", "k")));
        // Cohere is a native adapter, never routed through the OpenAI-compatible factory.
        assertThrows(IllegalArgumentException.class,
                () -> CompatibleModelFactory.settings("cohere", "command-r", Map.of("COHERE_API_KEY", "k")));
    }

    @Test
    void everyLongTailProviderCallsChatCompletionsWithBearerAuth() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var lastPath = new AtomicReference<String>();
        var lastAuth = new AtomicReference<String>();
        var lastModel = new AtomicReference<String>();
        var handler = (com.sun.net.httpserver.HttpHandler) exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody());
            lastModel.set(request.path("model").asText());
            respond(exchange, """
                    {"id":"x","object":"chat.completion","created":0,"model":"m",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"长尾回答"},
                     "finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                    """);
        };
        for (var testCase : CASES) server.createContext(testCase.pathPrefix(), handler);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (var testCase : CASES) {
                var env = new MockEnvironment()
                        .withProperty("FAST_LLM", testCase.provider() + ":model-x")
                        .withProperty(testCase.keyVariable(), "synthetic-" + testCase.provider())
                        .withProperty(testCase.baseVariable(), base + testCase.pathPrefix());
                var binding = new ConfiguredChatModels(env).binding("FAST", 128);
                assertEquals(testCase.provider(), binding.provider());
                var response = binding.client().call(new Prompt("长尾问题"));
                assertEquals("长尾回答", response.getResult().getOutput().getText());
                assertEquals(5, response.getMetadata().getUsage().getTotalTokens());
                assertEquals(testCase.pathPrefix() + "/chat/completions", lastPath.get());
                assertEquals("Bearer synthetic-" + testCase.provider(), lastAuth.get());
                assertEquals("model-x", lastModel.get());
                assertTrue(ProviderCatalog.usable(ProviderCatalog.Kind.CHAT, testCase.provider()));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void alternateCredentialNamesAreAccepted() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var lastAuth = new AtomicReference<String>();
        server.createContext("/v1", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, """
                    {"id":"x","object":"chat.completion","created":0,"model":"m",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                     "finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
                    """);
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var netmind = new MockEnvironment()
                    .withProperty("FAST_LLM", "netmind:model-x")
                    .withProperty("NETMIND_API_TOKEN", "token-netmind")
                    .withProperty("NETMIND_BASE_URL", base);
            assertEquals("ok", new ConfiguredChatModels(netmind).binding("FAST", 64)
                    .client().call(new Prompt("问题")).getResult().getOutput().getText());
            assertEquals("Bearer token-netmind", lastAuth.get());

            var huggingface = new MockEnvironment()
                    .withProperty("FAST_LLM", "huggingface:model-x")
                    .withProperty("HF_TOKEN", "token-hf")
                    .withProperty("HUGGINGFACE_BASE_URL", base);
            assertEquals("ok", new ConfiguredChatModels(huggingface).binding("FAST", 64)
                    .client().call(new Prompt("问题")).getResult().getOutput().getText());
            assertEquals("Bearer token-hf", lastAuth.get());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
