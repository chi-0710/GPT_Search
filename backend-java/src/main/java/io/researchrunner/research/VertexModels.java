package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A3-08: Google Vertex AI adapter for the Python {@code google_vertexai} provider.
 *
 * <p>Obtains an OAuth2 access token from a service-account key (RS256 JWT assertion at
 * {@code token_uri}) and calls {@code publishers/google/models/{model}:generateContent}. The
 * service-account JSON can be supplied inline ({@code GOOGLE_VERTEX_CREDENTIALS}) or by path
 * ({@code GOOGLE_APPLICATION_CREDENTIALS}). No Application Default Credentials helper is bundled:
 * users must provide a service-account key, which is what the Python provider expects in
 * non-GCP environments.
 */
public final class VertexModels {
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    private VertexModels() { }

    public static ChatModel create(String model, int maxTokens, Double temperature,
            Map<String, String> environment) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Vertex AI 模型不能为空");
        if (maxTokens < 1) throw new IllegalArgumentException("输出预算必须为正数");
        String credentials = credentialsJson(environment);
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(credentials);
        } catch (Exception e) {
            throw new IllegalArgumentException("Vertex AI 服务账号 JSON 无效", e);
        }
        String clientEmail = CustomChatSupport.text(root, "client_email");
        String privateKeyPem = CustomChatSupport.text(root, "private_key");
        if (clientEmail.isBlank()) throw new IllegalArgumentException("服务账号缺少 client_email");
        if (privateKeyPem.isBlank()) throw new IllegalArgumentException("服务账号缺少 private_key");
        String tokenUri = CustomChatSupport.text(root, "token_uri");
        if (tokenUri.isBlank()) tokenUri = DEFAULT_TOKEN_URI;
        String project = firstNonBlank(environment.get("GOOGLE_CLOUD_PROJECT"),
                environment.get("GOOGLE_VERTEX_PROJECT"), CustomChatSupport.text(root, "project_id"));
        if (project.isBlank()) throw new IllegalArgumentException("GOOGLE_CLOUD_PROJECT 未配置");
        String location = firstNonBlank(environment.get("GOOGLE_CLOUD_REGION"),
                environment.get("GOOGLE_VERTEX_LOCATION"), environment.get("GOOGLE_CLOUD_LOCATION"));
        if (location.isBlank()) location = "us-central1";
        String base = environment.get("GOOGLE_VERTEX_BASE_URL");
        if (base == null || base.isBlank()) base = "https://" + location + "-aiplatform.googleapis.com";
        base = CustomChatSupport.trimTrailingSlash(base.strip());
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("GOOGLE_VERTEX_BASE_URL 无效");
        }
        PrivateKey key = privateKey(privateKeyPem);
        URI endpoint = URI.create(base + "/v1/projects/" + enc(project) + "/locations/" + enc(location)
                + "/publishers/google/models/" + enc(model) + ":generateContent");
        return new VertexChatModel(model, maxTokens, temperature, endpoint, tokenUri,
                clientEmail, key, HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
                new ObjectMapper());
    }

    private static String credentialsJson(Map<String, String> environment) {
        String inline = environment.get("GOOGLE_VERTEX_CREDENTIALS");
        if (inline != null && !inline.isBlank()) return inline;
        String path = environment.get("GOOGLE_APPLICATION_CREDENTIALS");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("GOOGLE_APPLICATION_CREDENTIALS 或 GOOGLE_VERTEX_CREDENTIALS 未配置");
        }
        try {
            String content = Files.readString(Path.of(path.strip()), StandardCharsets.UTF_8);
            if (content.isBlank()) throw new IllegalArgumentException("服务账号文件为空");
            return content;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法读取服务账号文件", e);
        }
    }

    private static PrivateKey privateKey(String pem) {
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\n", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("服务账号私钥无效", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    private record VertexChatModel(String model, int maxTokens, Double temperature, URI endpoint,
            String tokenUri, String clientEmail, PrivateKey key, HttpClient client,
            ObjectMapper json, TokenCache tokens) implements ChatModel {

        VertexChatModel(String model, int maxTokens, Double temperature, URI endpoint,
                String tokenUri, String clientEmail, PrivateKey key, HttpClient client,
                ObjectMapper json) {
            this(model, maxTokens, temperature, endpoint, tokenUri, clientEmail, key, client, json,
                    new TokenCache());
        }

        @Override public ChatResponse call(Prompt prompt) {
            try {
                String body = requestBody(prompt);
                try {
                    return generate(body);
                } catch (IllegalStateException e) {
                    if (e.getMessage() != null && e.getMessage().contains("HTTP 401")) {
                        tokens.invalidate();
                        return generate(body);
                    }
                    throw e;
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Vertex AI 请求失败：" + e.getMessage(), e);
            }
        }

        private ChatResponse generate(String body) throws Exception {
            URI uri = endpoint;
            var response = client.send(java.net.http.HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + tokens.token(client, tokenUri, clientEmail, key, json))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            JsonNode root;
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(CustomChatSupport.MAX_RESPONSE_BYTES + 1);
                if (bytes.length > CustomChatSupport.MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("Vertex AI 响应过大");
                }
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Vertex AI 请求失败，HTTP " + response.statusCode());
                }
                root = json.readTree(bytes);
            }
            JsonNode candidates = root.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                throw new IllegalStateException("Vertex AI 未返回候选结果");
            }
            JsonNode candidate = candidates.get(0);
            StringBuilder text = new StringBuilder();
            JsonNode parts = candidate.path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) text.append(CustomChatSupport.text(part, "text"));
            }
            JsonNode usage = root.path("usageMetadata");
            Integer input = CustomChatSupport.integer(usage, "promptTokenCount");
            Integer output = CustomChatSupport.integer(usage, "candidatesTokenCount");
            String finish = switch (CustomChatSupport.text(candidate, "finishReason")
                    .toUpperCase(java.util.Locale.ROOT)) {
                case "STOP" -> "stop";
                case "MAX_TOKENS" -> "length";
                case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter";
                case "" -> "stop";
                default -> CustomChatSupport.text(candidate, "finishReason").toLowerCase(java.util.Locale.ROOT);
            };
            return CustomChatSupport.response(text.toString(), model, finish, input, output);
        }

        private String requestBody(Prompt prompt) throws Exception {
            List<Map<String, Object>> contents = new ArrayList<>();
            for (var message : CustomChatSupport.conversation(prompt)) {
                String role = switch (message.role()) {
                    case "assistant", "model" -> "model";
                    default -> "user";
                };
                contents.add(Map.of("role", role,
                        "parts", List.of(Map.of("text", message.content()))));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", contents);
            String system = CustomChatSupport.systemText(prompt);
            if (!system.isBlank()) body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
            Map<String, Object> generation = new LinkedHashMap<>();
            generation.put("maxOutputTokens", maxTokens);
            if (temperature != null) generation.put("temperature", temperature);
            body.put("generationConfig", generation);
            return json.writeValueAsString(body);
        }
    }

    /** Cached OAuth2 token; refreshed when fewer than 60 seconds remain. */
    private static final class TokenCache {
        private String accessToken;
        private Instant expiresAt = Instant.EPOCH;

        synchronized void invalidate() {
            accessToken = null;
            expiresAt = Instant.EPOCH;
        }

        synchronized String token(HttpClient client, String tokenUri, String clientEmail,
                PrivateKey key, ObjectMapper json) throws Exception {
            if (accessToken != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) {
                return accessToken;
            }
            String assertion = assertion(clientEmail, key, tokenUri, json);
            String form = "grant_type=" + URLEncoder.encode(
                    "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                    + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
            var response = client.send(java.net.http.HttpRequest.newBuilder(URI.create(tokenUri))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            JsonNode root;
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(CustomChatSupport.MAX_RESPONSE_BYTES + 1);
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Vertex AI 认证失败，HTTP " + response.statusCode());
                }
                root = json.readTree(bytes);
            }
            String token = CustomChatSupport.text(root, "access_token");
            if (token.isBlank()) throw new IllegalStateException("Vertex AI 认证未返回 access_token");
            int expiresIn = CustomChatSupport.integer(root, "expires_in") == null
                    ? 3600 : CustomChatSupport.integer(root, "expires_in");
            accessToken = token;
            expiresAt = Instant.now().plusSeconds(Math.max(expiresIn, 0));
            return token;
        }

        private String assertion(String clientEmail, PrivateKey key, String tokenUri,
                ObjectMapper json) throws Exception {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", clientEmail);
            claims.put("scope", SCOPE);
            claims.put("aud", tokenUri);
            claims.put("iat", now);
            claims.put("exp", now + 3600);
            String header = b64(json.writeValueAsString(Map.of("alg", "RS256", "typ", "JWT"))
                    .getBytes(StandardCharsets.UTF_8));
            String payload = b64(json.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(signature.sign());
        }

        private static String b64(byte[] bytes) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
}
