package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;

/** A3-08: local protocol test for Vertex AI (service-account OAuth2 + generateContent). */
class VertexModelsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exchangesServiceAccountJwtForTokenAndCallsGenerateContent() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        var tokenRequests = new AtomicInteger();
        var tokenAuthorization = new AtomicReference<String>();
        var assertion = new AtomicReference<String>();
        var bearer = new AtomicReference<String>();
        var path = new AtomicReference<String>();
        var request = new AtomicReference<JsonNode>();

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        server.createContext("/token", exchange -> {
            tokenRequests.incrementAndGet();
            tokenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String pair : form.split("&")) {
                int split = pair.indexOf('=');
                if (split > 0 && pair.substring(0, split).equals("assertion")) {
                    assertion.set(URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
                }
            }
            respond(exchange, """
                    {"access_token":"synthetic-vertex-token","expires_in":3600,"token_type":"Bearer"}
                    """);
        });
        server.createContext("/v1", exchange -> {
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            request.set(json.readTree(exchange.getRequestBody()));
            respond(exchange, """
                    {"candidates":[{"content":{"role":"model","parts":[
                        {"text":"你好"},{"text":null},{"text":"世界"}]},
                        "finishReason":"STOP"}],
                     "usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":4,"totalTokenCount":15}}
                    """);
        });
        server.start();
        try {
            var env = vertexEnvironment(server, keyPair, tokenUrl);
            var binding = new ConfiguredChatModels(env).binding("FAST", 512);
            assertEquals("google_vertexai", binding.provider());
            ChatResponse response = binding.client().call(new Prompt(List.of(
                    new SystemMessage("系统指令"), new UserMessage("用户问题"))));

            assertEquals("你好世界", response.getResult().getOutput().getText());
            assertEquals("stop", response.getResult().getMetadata().getFinishReason());
            assertEquals(11, response.getMetadata().getUsage().getPromptTokens());
            assertEquals(4, response.getMetadata().getUsage().getCompletionTokens());

            assertEquals("Bearer synthetic-vertex-token", bearer.get());
            assertEquals("/v1/projects/proj/locations/us-central1/publishers/google/models/"
                    + "gemini-2.5-pro:generateContent", path.get());
            assertEquals("user", request.get().path("contents").get(0).path("role").asText());
            assertEquals("用户问题",
                    request.get().path("contents").get(0).path("parts").get(0).path("text").asText());
            assertEquals("系统指令",
                    request.get().path("systemInstruction").path("parts").get(0).path("text").asText());
            assertEquals(512, request.get().path("generationConfig").path("maxOutputTokens").asInt());

            // The service-account JWT must be a valid RS256 assertion for the token endpoint.
            String jwt = assertion.get();
            assertTrue(jwt != null && jwt.chars().filter(c -> c == '.').count() == 2);
            String[] parts = jwt.split("\\.");
            JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            assertEquals("RS256", header.path("alg").asText());
            assertEquals("sa@proj.iam.gserviceaccount.com", claims.path("iss").asText());
            assertEquals("https://www.googleapis.com/auth/cloud-platform", claims.path("scope").asText());
            assertEquals(tokenUrl, claims.path("aud").asText());
            assertEquals(3600, claims.path("exp").asLong() - claims.path("iat").asLong());
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keyPair.getPublic());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));

            // A second call reuses the cached access token.
            binding.client().call(new Prompt("再次提问"));
            assertEquals(1, tokenRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void toleratesMissingCandidateTextAndReportsEmptyCandidates() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        var payload = new AtomicReference<>(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":null}]},\"finishReason\":\"MAX_TOKENS\"}]}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        server.createContext("/token", exchange -> respond(exchange,
                "{\"access_token\":\"synthetic\",\"expires_in\":3600}"));
        server.createContext("/v1", exchange -> respond(exchange, payload.get()));
        server.start();
        try {
            var env = vertexEnvironment(server, keyPair, tokenUrl);
            var binding = new ConfiguredChatModels(env).binding("FAST", 128);
            ChatResponse response = binding.client().call(new Prompt("问题"));
            assertEquals("", response.getResult().getOutput().getText());
            assertEquals("length", response.getResult().getMetadata().getFinishReason());

            payload.set("{\"candidates\":[]}");
            var empty = assertThrows(IllegalStateException.class,
                    () -> binding.client().call(new Prompt("问题")));
            assertTrue(empty.getMessage().contains("候选"));

            payload.set("{}");
            assertThrows(IllegalStateException.class, () -> binding.client().call(new Prompt("问题")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authenticationFailureAndConfigurationErrorsAreExplicit() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        server.createContext("/token", exchange -> {
            byte[] body = "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var env = vertexEnvironment(server, keyPair, tokenUrl);
            var binding = new ConfiguredChatModels(env).binding("FAST", 128);
            var error = assertThrows(IllegalStateException.class,
                    () -> binding.client().call(new Prompt("问题")));
            assertTrue(error.getMessage().contains("认证"));

            var noCredentials = new MockEnvironment().withProperty("FAST_LLM", "google_vertexai:gemini-2.5-pro");
            assertThrows(IllegalArgumentException.class,
                    () -> new ConfiguredChatModels(noCredentials).binding("FAST", 128));

            var noProject = new MockEnvironment()
                    .withProperty("FAST_LLM", "google_vertexai:gemini-2.5-pro")
                    .withProperty("GOOGLE_VERTEX_CREDENTIALS", serviceAccount(keyPair, tokenUrl, null).toString());
            assertThrows(IllegalArgumentException.class,
                    () -> new ConfiguredChatModels(noProject).binding("FAST", 128));
        } finally {
            server.stop(0);
        }
    }

    private MockEnvironment vertexEnvironment(HttpServer server, KeyPair keyPair, String tokenUrl) throws Exception {
        return new MockEnvironment()
                .withProperty("FAST_LLM", "google_vertexai:gemini-2.5-pro")
                .withProperty("GOOGLE_VERTEX_CREDENTIALS", serviceAccount(keyPair, tokenUrl, "proj").toString())
                .withProperty("GOOGLE_CLOUD_PROJECT", "proj")
                .withProperty("GOOGLE_CLOUD_REGION", "us-central1")
                .withProperty("GOOGLE_VERTEX_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("TEMPERATURE", "0.1");
    }

    private JsonNode serviceAccount(KeyPair keyPair, String tokenUrl, String projectId) throws Exception {
        Map<String, Object> account = projectId == null ? Map.of(
                "type", "service_account",
                "client_email", "sa@proj.iam.gserviceaccount.com",
                "private_key", pem(keyPair),
                "token_uri", tokenUrl) : Map.of(
                "type", "service_account",
                "client_email", "sa@proj.iam.gserviceaccount.com",
                "private_key", pem(keyPair),
                "token_uri", tokenUrl,
                "project_id", projectId);
        return json.valueToTree(account);
    }

    static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static String pem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
