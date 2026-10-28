package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A3-08: Amazon Bedrock adapter (AWS SigV4 + {@code InvokeModel}).
 *
 * <p>Signing follows the algorithm used by the AWS SDKs and botocore: the logical path is
 * URI-encoded once for the wire request and once again for the canonical URI, which is what AWS
 * expects outside S3. Supported body/response families: Anthropic Claude Messages, Amazon Titan
 * text, Meta Llama and Cohere Command. Unsupported families fail with an explicit error instead
 * of sending a guessed payload.
 */
public final class BedrockModels {
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final String SERVICE = "bedrock";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private BedrockModels() { }

    public static ChatModel create(String model, int maxTokens, Double temperature,
            Map<String, String> environment) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Bedrock 模型不能为空");
        if (maxTokens < 1) throw new IllegalArgumentException("输出预算必须为正数");
        String accessKey = firstNonBlank(environment.get("AWS_ACCESS_KEY_ID"),
                environment.get("AWS_ACCESS_KEY"));
        if (accessKey.isBlank()) throw new IllegalArgumentException("AWS_ACCESS_KEY_ID 未配置");
        String secretKey = firstNonBlank(environment.get("AWS_SECRET_ACCESS_KEY"),
                environment.get("AWS_SECRET_KEY"));
        if (secretKey.isBlank()) throw new IllegalArgumentException("AWS_SECRET_ACCESS_KEY 未配置");
        String sessionToken = firstNonBlank(environment.get("AWS_SESSION_TOKEN"));
        String region = firstNonBlank(environment.get("AWS_REGION"), environment.get("AWS_DEFAULT_REGION"));
        if (region.isBlank()) region = "us-east-1";
        String base = environment.get("BEDROCK_BASE_URL");
        if (base == null || base.isBlank()) base = "https://bedrock-runtime." + region + ".amazonaws.com";
        base = CustomChatSupport.trimTrailingSlash(base.strip());
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("BEDROCK_BASE_URL 无效");
        }
        Family family = Family.of(model);
        String sentPath = uriEncode("/model/" + model + "/invoke");
        URI endpoint = URI.create(base + sentPath);
        return new BedrockChatModel(model, maxTokens, temperature, family, region, accessKey,
                secretKey, sessionToken.isBlank() ? null : sessionToken, endpoint,
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), new ObjectMapper());
    }

    enum Family {
        ANTHROPIC, TITAN, LLAMA, COHERE;

        static Family of(String model) {
            String lower = model.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("anthropic.")) return ANTHROPIC;
            if (lower.startsWith("amazon.titan")) return TITAN;
            if (lower.startsWith("meta.llama")) return LLAMA;
            if (lower.startsWith("cohere.command")) return COHERE;
            throw new IllegalArgumentException("不支持的 Bedrock 模型族：" + model);
        }
    }

    private record BedrockChatModel(String model, int maxTokens, Double temperature, Family family,
            String region, String accessKey, String secretKey, String sessionToken, URI endpoint,
            HttpClient client, ObjectMapper json) implements ChatModel {

        @Override public ChatResponse call(Prompt prompt) {
            try {
                String body = json.writeValueAsString(requestBody(prompt));
                Instant now = Instant.now();
                String host = endpoint.getHost();
                boolean defaultPort = endpoint.getPort() == -1
                        || ("https".equals(endpoint.getScheme()) && endpoint.getPort() == 443)
                        || ("http".equals(endpoint.getScheme()) && endpoint.getPort() == 80);
                if (!defaultPort) host = host + ":" + endpoint.getPort();
                Signed signed = sign("POST", host, endpoint.getRawPath(), body, region,
                        accessKey, secretKey, sessionToken, "application/json", now);
                var builder = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("X-Amz-Date", signed.amzDate())
                        .header("Authorization", signed.authorization())
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (sessionToken != null) builder.header("X-Amz-Security-Token", sessionToken);
                var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                JsonNode root;
                try (var stream = response.body()) {
                    byte[] bytes = stream.readNBytes(CustomChatSupport.MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > CustomChatSupport.MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("Bedrock 响应过大");
                    }
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Bedrock 请求失败，HTTP " + response.statusCode());
                    }
                    root = json.readTree(bytes);
                }
                return parse(root);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Bedrock 请求失败：" + e.getMessage(), e);
            }
        }

        private Map<String, Object> requestBody(Prompt prompt) {
            return switch (family) {
                case ANTHROPIC -> anthropicBody(prompt);
                case TITAN -> titanBody(prompt);
                case LLAMA -> llamaBody(prompt);
                case COHERE -> cohereBody(prompt);
            };
        }

        private Map<String, Object> anthropicBody(Prompt prompt) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (var message : CustomChatSupport.conversation(prompt)) {
                String role = message.role().equals("assistant") ? "assistant" : "user";
                messages.add(Map.of("role", role,
                        "content", List.of(Map.of("type", "text", "text", message.content()))));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("anthropic_version", "bedrock-2023-05-31");
            body.put("max_tokens", maxTokens);
            body.put("messages", messages);
            String system = CustomChatSupport.systemText(prompt);
            if (!system.isBlank()) body.put("system", system);
            if (temperature != null) body.put("temperature", temperature);
            return body;
        }

        private Map<String, Object> titanBody(Prompt prompt) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("maxTokenCount", maxTokens);
            config.put("topP", 1);
            if (temperature != null) config.put("temperature", temperature);
            return Map.of("inputText", flatten(prompt), "textGenerationConfig", config);
        }

        private Map<String, Object> llamaBody(Prompt prompt) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", flatten(prompt));
            body.put("max_gen_len", maxTokens);
            if (temperature != null) body.put("temperature", temperature);
            return body;
        }

        private Map<String, Object> cohereBody(Prompt prompt) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", flatten(prompt));
            body.put("max_tokens", maxTokens);
            body.put("return_likelihoods", "NONE");
            if (temperature != null) body.put("temperature", temperature);
            return body;
        }

        private String flatten(Prompt prompt) {
            String system = CustomChatSupport.systemText(prompt);
            StringBuilder text = new StringBuilder(system);
            for (var message : CustomChatSupport.conversation(prompt)) {
                if (text.length() > 0) text.append("\n\n");
                text.append(message.content());
            }
            return text.toString();
        }

        private ChatResponse parse(JsonNode root) {
            return switch (family) {
                case ANTHROPIC -> {
                    StringBuilder text = new StringBuilder();
                    JsonNode content = root.get("content");
                    if (content != null && content.isArray()) {
                        for (JsonNode part : content) {
                            if ("text".equals(CustomChatSupport.text(part, "type"))) {
                                text.append(CustomChatSupport.text(part, "text"));
                            }
                        }
                    }
                    String stop = CustomChatSupport.text(root, "stop_reason");
                    String finish = switch (stop) {
                        case "max_tokens" -> "length";
                        case "end_turn", "stop_sequence", "" -> "stop";
                        default -> stop;
                    };
                    yield CustomChatSupport.response(text.toString(), model, finish,
                            CustomChatSupport.integer(root.path("usage"), "input_tokens"),
                            CustomChatSupport.integer(root.path("usage"), "output_tokens"));
                }
                case TITAN -> {
                    JsonNode result = root.path("results").path(0);
                    String text = CustomChatSupport.text(result, "outputText");
                    String finish = switch (CustomChatSupport.text(result, "completionReason")) {
                        case "LENGTH" -> "length";
                        case "FINISH", "" -> "stop";
                        default -> CustomChatSupport.text(result, "completionReason").toLowerCase(java.util.Locale.ROOT);
                    };
                    yield CustomChatSupport.response(text, model, finish,
                            CustomChatSupport.integer(root, "inputTextTokenCount"),
                            CustomChatSupport.integer(result, "tokenCount"));
                }
                case LLAMA -> {
                    String stop = CustomChatSupport.text(root, "stop_reason");
                    String finish = "length".equals(stop) ? "length" : "stop";
                    yield CustomChatSupport.response(CustomChatSupport.text(root, "generation"), model,
                            finish, CustomChatSupport.integer(root, "prompt_token_count"),
                            CustomChatSupport.integer(root, "generation_token_count"));
                }
                case COHERE -> {
                    JsonNode generation = root.path("generations").path(0);
                    String finish = switch (CustomChatSupport.text(generation, "finish_reason")) {
                        case "MAX_TOKENS" -> "length";
                        default -> "stop";
                    };
                    yield CustomChatSupport.response(CustomChatSupport.text(generation, "text"), model,
                            finish, CustomChatSupport.integer(root.path("meta").path("billed_units"), "input_tokens"),
                            CustomChatSupport.integer(root.path("meta").path("billed_units"), "output_tokens"));
                }
            };
        }
    }

    record Signed(String amzDate, String authorization) { }

    /**
     * Signs a Bedrock request. {@code sentPath} is the already URI-encoded HTTP path (as it appears
     * on the wire); the canonical URI encodes it a second time, matching the AWS SDK behavior.
     */
    static Signed sign(String method, String host, String sentPath, String body, String region,
            String accessKey, String secretKey, String sessionToken, String contentType,
            Instant timestamp) {
        return sign(method, host, sentPath, body, region, accessKey, secretKey, sessionToken,
                contentType, SERVICE, timestamp);
    }

    static Signed sign(String method, String host, String sentPath, String body, String region,
            String accessKey, String secretKey, String sessionToken, String contentType,
            String service, Instant timestamp) {
        String amzDate = AMZ_DATE.format(timestamp);
        String dateStamp = amzDate.substring(0, 8);
        String canonicalUri = uriEncode(sentPath);
        String payloadHash = hex(sha256(body.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", host);
        headers.put("x-amz-date", amzDate);
        if (contentType != null) headers.put("content-type", contentType);
        if (sessionToken != null) headers.put("x-amz-security-token", sessionToken);
        List<String> names = new ArrayList<>(headers.keySet());
        names.sort(String::compareTo);
        StringBuilder canonicalHeaders = new StringBuilder();
        for (String name : names) canonicalHeaders.append(name).append(':').append(headers.get(name)).append('\n');
        String signedHeaders = String.join(";", names);

        String canonicalRequest = method + "\n" + canonicalUri + "\n" + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] signingKey = hmac(hmac(hmac(hmac(
                ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp),
                region), service), "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new Signed(amzDate, authorization);
    }

    /** AWS UriEncode: unreserved characters are preserved, everything else becomes %XX uppercase. */
    static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xFF;
            char c = (char) b;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/') {
                encoded.append(c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0x0F, 16)));
            }
        }
        return encoded.toString();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format("%02x", b));
        return text.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }
}
