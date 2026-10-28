package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;

/** A3-08: local protocol test for the native Cohere {@code /v2/chat} adapter. */
class CohereModelsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsV2ChatRequestAndMapsContentAndUsage() throws Exception {
        var authorization = new AtomicReference<String>();
        var request = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/chat", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(json.readTree(exchange.getRequestBody()));
            respond(exchange, """
                    {"id":"abc","message":{"role":"assistant","content":[
                       {"type":"text","text":"你好，"},
                       {"type":"thinking","thinking":"hidden"},
                       {"type":"text","text":"世界"}]},
                     "finish_reason":"COMPLETE",
                     "usage":{"tokens":{"input_tokens":7,"output_tokens":3}}}
                    """);
        });
        server.start();
        try {
            var env = new MockEnvironment()
                    .withProperty("FAST_LLM", "cohere:command-r-plus")
                    .withProperty("COHERE_API_KEY", "synthetic-cohere")
                    .withProperty("COHERE_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort())
                    .withProperty("TEMPERATURE", "0.2");
            var binding = new ConfiguredChatModels(env).binding("FAST", 256);
            assertEquals("cohere", binding.provider());
            ChatResponse response = binding.client().call(new Prompt(List.of(
                    new SystemMessage("系统提示"), new UserMessage("用户问题"))));
            assertEquals("你好，世界", response.getResult().getOutput().getText());
            assertEquals(7, response.getMetadata().getUsage().getPromptTokens());
            assertEquals(3, response.getMetadata().getUsage().getCompletionTokens());
            assertEquals("stop", response.getResult().getMetadata().getFinishReason());

            assertEquals("Bearer synthetic-cohere", authorization.get());
            assertEquals("command-r-plus", request.get().path("model").asText());
            assertEquals(256, request.get().path("max_tokens").asInt());
            assertEquals(0.2, request.get().path("temperature").asDouble(), 0.0001);
            assertEquals("system", request.get().path("messages").get(0).path("role").asText());
            assertEquals("系统提示", request.get().path("messages").get(0).path("content").asText());
            assertEquals("user", request.get().path("messages").get(1).path("role").asText());
            assertEquals("用户问题", request.get().path("messages").get(1).path("content").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void toleratesNullContentFieldsAndMissingUsage() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/chat", exchange -> respond(exchange, """
                {"message":{"content":[{"type":"text","text":null},{"text":"保留正文"}]},
                 "finish_reason":null,"usage":null}
                """));
        server.start();
        try {
            var env = new MockEnvironment()
                    .withProperty("SMART_LLM", "cohere:command-r")
                    .withProperty("COHERE_API_KEY", "synthetic-cohere")
                    .withProperty("COHERE_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            ChatResponse response = new ConfiguredChatModels(env).binding("SMART", 128)
                    .client().call(new Prompt("问题"));
            assertEquals("保留正文", response.getResult().getOutput().getText());
            assertEquals("stop", response.getResult().getMetadata().getFinishReason());
            assertNotNull(response.getMetadata().getUsage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authenticationFailureIsExplicitAndNotRetried() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/chat", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"message\":\"invalid api token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var env = new MockEnvironment()
                    .withProperty("FAST_LLM", "cohere:command-r")
                    .withProperty("COHERE_API_KEY", "bad")
                    .withProperty("COHERE_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            var binding = new ConfiguredChatModels(env).binding("FAST", 128);
            var error = assertThrows(IllegalStateException.class,
                    () -> binding.client().call(new Prompt("问题")));
            assertTrue(error.getMessage().contains("401"));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configurationErrorsAreExplicit() {
        assertThrows(IllegalArgumentException.class,
                () -> CohereModels.create("command-r", 100, 0.0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> CohereModels.create(" ", 100, 0.0, "key", null));
        assertThrows(IllegalArgumentException.class,
                () -> CohereModels.create("command-r", 0, 0.0, "key", null));
        var env = new MockEnvironment().withProperty("FAST_LLM", "cohere:command-r");
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredChatModels(env).binding("FAST", 100));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
