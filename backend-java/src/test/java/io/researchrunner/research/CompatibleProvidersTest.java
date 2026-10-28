package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;

/** A3-06/A3-07: Ollama and other OpenAI-compatible providers use the shared adapter. */
class CompatibleProvidersTest {
    @Test
    void ollamaWorksWithoutARealApiKey() throws Exception {
        var json = new ObjectMapper();
        var path = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            String response = """
                    {"id":"x","object":"chat.completion","created":0,"model":"llama3",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"本地回答"},
                     "finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var env = new MockEnvironment()
                    .withProperty("FAST_LLM", "ollama:llama3")
                    .withProperty("OLLAMA_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var binding = new ConfiguredChatModels(env).binding("FAST", 512);
            var response = binding.client().call(new Prompt("你好"));
            assertEquals("本地回答", response.getResult().getOutput().getText());
            assertEquals("/v1/chat/completions", path.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void catalogAllowsGroqAndMistralThroughCompatibilityLayer() {
        assertTrue(ProviderCatalog.usable(ProviderCatalog.Kind.CHAT, "groq"));
        assertTrue(ProviderCatalog.usable(ProviderCatalog.Kind.CHAT, "mistralai"));
        assertTrue(ProviderCatalog.usable(ProviderCatalog.Kind.CHAT, "xai"));
    }
}
