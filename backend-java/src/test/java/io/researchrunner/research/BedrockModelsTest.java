package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;

/** A3-08: local protocol test for Bedrock SigV4 signing and InvokeModel body/response mapping. */
class BedrockModelsTest {
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final Instant TIMESTAMP = Instant.parse("2025-08-30T12:36:00Z");
    private static final String CLAUDE_BODY =
            "{\"anthropic_version\":\"bedrock-2023-05-31\",\"max_tokens\":16,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CLAUDE_PATH =
            "/model/anthropic.claude-3-5-sonnet-20240620-v1%3A0/invoke";
    private static final String CLAUDE_HOST = "bedrock-runtime.us-east-1.amazonaws.com";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void signaturesMatchIndependentAwsSigV4GoldenVectors() {
        // Golden vectors generated with botocore 1.34 SigV4Auth for the same inputs.
        var post = BedrockModels.sign("POST", CLAUDE_HOST, CLAUDE_PATH, CLAUDE_BODY, "us-east-1",
                "AKIDEXAMPLE", SECRET, null, "application/json", "bedrock", TIMESTAMP);
        assertEquals("20250830T123600Z", post.amzDate());
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20250830/us-east-1/bedrock/aws4_request"
                + ", SignedHeaders=content-type;host;x-amz-date"
                + ", Signature=b040d9c30413faecdf21713ddf502e1cec5d6dfa8ace229ccc4d1606f6a5e807",
                post.authorization());

        var get = BedrockModels.sign("GET", "example.amazonaws.com", "/", "", "us-east-1",
                "AKIDEXAMPLE", SECRET, null, null, "service", TIMESTAMP);
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20250830/us-east-1/service/aws4_request"
                + ", SignedHeaders=host;x-amz-date"
                + ", Signature=b303193389225a6ad7e37de685bb3bfa123f5c4fc52779258bb0e5c73c4c737a",
                get.authorization());

        var session = BedrockModels.sign("POST", CLAUDE_HOST, CLAUDE_PATH, CLAUDE_BODY, "us-east-1",
                "AKIDEXAMPLE", SECRET, "SESSIONTOKENEXAMPLE", "application/json", "bedrock", TIMESTAMP);
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20250830/us-east-1/bedrock/aws4_request"
                + ", SignedHeaders=content-type;host;x-amz-date;x-amz-security-token"
                + ", Signature=b3fb755e1bdc1b7fc5e7be65ee92fa4df30a67ef351f08a6cf1a94937dcd3ab7",
                session.authorization());
    }

    @Test
    void anthropicInvokeSendsSignedClaudeBodyAndMapsResponse() throws Exception {
        var authorization = new AtomicReference<String>();
        var amzDate = new AtomicReference<String>();
        var sessionToken = new AtomicReference<String>();
        var rawPath = new AtomicReference<String>();
        var body = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            amzDate.set(exchange.getRequestHeaders().getFirst("X-Amz-Date"));
            sessionToken.set(exchange.getRequestHeaders().getFirst("X-Amz-Security-Token"));
            rawPath.set(exchange.getRequestURI().getRawPath());
            body.set(json.readTree(exchange.getRequestBody()));
            respond(exchange, """
                    {"id":"msg","type":"message","role":"assistant","content":[
                       {"type":"text","text":"克里"},
                       {"type":"tool_use","id":"t1"},
                       {"type":"text","text":"斯"}],
                     "stop_reason":"end_turn",
                     "usage":{"input_tokens":8,"output_tokens":5}}
                    """);
        });
        server.start();
        try {
            var binding = new ConfiguredChatModels(bedrockEnvironment(server, null))
                    .binding("FAST", 300);
            assertEquals("bedrock", binding.provider());
            ChatResponse response = binding.client().call(new Prompt(
                    java.util.List.of(new SystemMessage("系统"), new UserMessage("问题"))));

            assertEquals("克里斯", response.getResult().getOutput().getText());
            assertEquals("stop", response.getResult().getMetadata().getFinishReason());
            assertEquals(8, response.getMetadata().getUsage().getPromptTokens());
            assertEquals(5, response.getMetadata().getUsage().getCompletionTokens());

            assertEquals("/model/anthropic.claude-3-5-sonnet-20240620-v1%3A0/invoke", rawPath.get());
            assertTrue(authorization.get().startsWith(
                    "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"));
            assertTrue(authorization.get().contains(
                    "/bedrock/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature="));
            assertTrue(amzDate.get() != null && amzDate.get().matches("\\d{8}T\\d{6}Z"));
            assertEquals(null, sessionToken.get());

            assertEquals("bedrock-2023-05-31", body.get().path("anthropic_version").asText());
            assertEquals(300, body.get().path("max_tokens").asInt());
            assertEquals("系统", body.get().path("system").asText());
            assertEquals("user", body.get().path("messages").get(0).path("role").asText());
            assertEquals("问题", body.get().path("messages").get(0).path("content").get(0).path("text").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void temporaryCredentialsAddSecurityTokenHeader() throws Exception {
        var authorization = new AtomicReference<String>();
        var sessionToken = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sessionToken.set(exchange.getRequestHeaders().getFirst("X-Amz-Security-Token"));
            respond(exchange, """
                    {"content":[{"type":"text","text":"ok"}],"stop_reason":"stop_sequence",
                     "usage":{"input_tokens":1,"output_tokens":1}}
                    """);
        });
        server.start();
        try {
            var binding = new ConfiguredChatModels(bedrockEnvironment(server, "SESSIONTOKENEXAMPLE"))
                    .binding("FAST", 64);
            assertEquals("ok", binding.client().call(new Prompt("问题"))
                    .getResult().getOutput().getText());
            assertEquals("SESSIONTOKENEXAMPLE", sessionToken.get());
            assertTrue(authorization.get().contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void titanLlamaAndCohereFamiliesUseTheirOwnBodiesAndResponses() throws Exception {
        var lastBody = new AtomicReference<JsonNode>();
        var lastPath = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            lastBody.set(json.readTree(exchange.getRequestBody()));
            lastPath.set(exchange.getRequestURI().getRawPath());
            if (lastPath.get().contains("titan")) {
                respond(exchange, """
                        {"results":[{"outputText":"泰坦","tokenCount":3,"completionReason":"FINISH"}],
                         "inputTextTokenCount":4}
                        """);
            } else if (lastPath.get().contains("llama")) {
                respond(exchange, """
                        {"generation":"羊驼","prompt_token_count":2,"generation_token_count":6,
                         "stop_reason":"stop"}
                        """);
            } else {
                respond(exchange, """
                        {"generations":[{"text":"可海","finish_reason":"COMPLETE"}],
                         "meta":{"billed_units":{"input_tokens":3,"output_tokens":2}}}
                        """);
            }
        });
        server.start();
        try {
            var env = bedrockEnvironment(server, null);
            var titan = new ConfiguredChatModels(env.withProperty("FAST_LLM", "bedrock:amazon.titan-text-express-v1"))
                    .binding("FAST", 128);
            ChatResponse titanResponse = titan.client().call(new Prompt("泰坦问题"));
            assertEquals("泰坦", titanResponse.getResult().getOutput().getText());
            assertEquals(4, titanResponse.getMetadata().getUsage().getPromptTokens());
            assertEquals(3, titanResponse.getMetadata().getUsage().getCompletionTokens());
            assertEquals("泰坦问题", lastBody.get().path("inputText").asText());
            assertEquals(128, lastBody.get().path("textGenerationConfig").path("maxTokenCount").asInt());

            var llama = new ConfiguredChatModels(env.withProperty("FAST_LLM", "bedrock:meta.llama3-8b-instruct-v1:0"))
                    .binding("FAST", 256);
            ChatResponse llamaResponse = llama.client().call(new Prompt("羊驼问题"));
            assertEquals("羊驼", llamaResponse.getResult().getOutput().getText());
            assertEquals(2, llamaResponse.getMetadata().getUsage().getPromptTokens());
            assertEquals("羊驼问题", lastBody.get().path("prompt").asText());
            assertEquals(256, lastBody.get().path("max_gen_len").asInt());

            var cohere = new ConfiguredChatModels(env.withProperty("FAST_LLM", "bedrock:cohere.command-r-v1:0"))
                    .binding("FAST", 64);
            ChatResponse cohereResponse = cohere.client().call(new Prompt("可海问题"));
            assertEquals("可海", cohereResponse.getResult().getOutput().getText());
            assertEquals(3, cohereResponse.getMetadata().getUsage().getPromptTokens());
            assertEquals("可海问题", lastBody.get().path("prompt").asText());
            assertEquals("NONE", lastBody.get().path("return_likelihoods").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpErrorUnsupportedFamilyAndConfigurationAreExplicit() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"message\":\"AccessDeniedException\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var binding = new ConfiguredChatModels(bedrockEnvironment(server, null)).binding("FAST", 64);
            var error = assertThrows(IllegalStateException.class,
                    () -> binding.client().call(new Prompt("问题")));
            assertTrue(error.getMessage().contains("403"));
            assertEquals(1, calls.get());

            var badFamily = assertThrows(IllegalArgumentException.class,
                    () -> BedrockModels.create("mistral.mistral-7b-instruct-v0:2", 100, 0.0,
                            java.util.Map.of("AWS_ACCESS_KEY_ID", "a", "AWS_SECRET_ACCESS_KEY", "b")));
            assertTrue(badFamily.getMessage().contains("模型族"));

            assertThrows(IllegalArgumentException.class, () -> BedrockModels.create(
                    "anthropic.claude-3-haiku-20240307-v1:0", 100, 0.0, java.util.Map.of()));
            assertThrows(IllegalArgumentException.class, () -> new ConfiguredChatModels(
                    new MockEnvironment().withProperty("FAST_LLM", "bedrock:anthropic.claude-3-haiku-20240307-v1:0"))
                    .binding("FAST", 64));
        } finally {
            server.stop(0);
        }
    }

    private MockEnvironment bedrockEnvironment(HttpServer server, String sessionToken) {
        var env = new MockEnvironment()
                .withProperty("FAST_LLM", "bedrock:anthropic.claude-3-5-sonnet-20240620-v1:0")
                .withProperty("AWS_ACCESS_KEY_ID", "AKIDEXAMPLE")
                .withProperty("AWS_SECRET_ACCESS_KEY", SECRET)
                .withProperty("AWS_REGION", "us-east-1")
                .withProperty("BEDROCK_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("TEMPERATURE", "0.1");
        if (sessionToken != null) env = env.withProperty("AWS_SESSION_TOKEN", sessionToken);
        return env;
    }

    private static void respond(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
