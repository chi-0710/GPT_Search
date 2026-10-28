package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A3-13 (protocol level): Google Gemini/Imagen image generation.
 *
 * <p>The research graph does not yet insert generated images into the report; this adapter
 * establishes the provider boundary and request/response contract so the artifact pipeline can
 * be wired once the publication integration is implemented.
 */
public final class GoogleImageProvider {
    public record Image(byte[] bytes, String mimeType) { }

    private final HttpClient client;
    private final ObjectMapper json;
    private final String apiKey;
    private final String model;
    private final URI baseUrl;
    private final Duration timeout;

    public GoogleImageProvider(HttpClient client, ObjectMapper json, String apiKey, String model,
            String baseUrl, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("GOOGLE_API_KEY 未配置");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("图片模型不能为空");
        this.client = client;
        this.json = json;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = URI.create(baseUrl == null || baseUrl.isBlank()
                ? "https://generativelanguage.googleapis.com" : baseUrl.replaceAll("/+$", ""));
        this.timeout = timeout;
    }

    public Image generate(String prompt) throws Exception {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("图片提示词不能为空");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))));
        body.put("generationConfig", Map.of("responseModalities", List.of("TEXT", "IMAGE")));
        String modelPath = model.startsWith("models/") ? model : "models/" + model;
        URI uri = URI.create(baseUrl + "/v1beta/" + modelPath + ":generateContent?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("图片生成失败，HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IllegalStateException("图片响应过大");
            var root = json.readTree(bytes);
            for (var candidate : root.path("candidates")) {
                for (var part : candidate.path("content").path("parts")) {
                    var inline = part.path("inlineData");
                    if (!inline.path("data").asText("").isBlank()) {
                        return new Image(Base64.getDecoder().decode(inline.path("data").asText()),
                                inline.path("mimeType").asText("image/png"));
                    }
                }
            }
            throw new IllegalStateException("图片响应缺少内联图像数据");
        }
    }
}
