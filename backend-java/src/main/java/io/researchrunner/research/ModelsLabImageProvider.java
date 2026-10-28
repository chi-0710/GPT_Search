package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A3-13 (protocol level): ModelsLab text-to-image adapter with bounded polling.
 *
 * <p>Not yet connected to the report/artifact publication pipeline; only the provider boundary
 * is implemented and verified.
 */
public final class ModelsLabImageProvider {
    public record Image(byte[] bytes, String mimeType) { }

    private final HttpClient client;
    private final ObjectMapper json;
    private final String apiKey;
    private final URI endpoint;
    private final Duration timeout;
    private final boolean allowPrivate;
    private final int maxPollAttempts;

    public ModelsLabImageProvider(HttpClient client, ObjectMapper json, String apiKey, String endpoint,
            Duration timeout, boolean allowPrivate) {
        this(client, json, apiKey, endpoint, timeout, allowPrivate, 12);
    }

    ModelsLabImageProvider(HttpClient client, ObjectMapper json, String apiKey, String endpoint,
            Duration timeout, boolean allowPrivate, int maxPollAttempts) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("MODELS_LAB_API_KEY 未配置");
        this.client = client;
        this.json = json;
        this.apiKey = apiKey;
        this.endpoint = URI.create(endpoint);
        this.timeout = timeout;
        this.allowPrivate = allowPrivate;
        this.maxPollAttempts = Math.max(1, maxPollAttempts);
    }

    public Image generate(String prompt) throws Exception {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("图片提示词不能为空");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", apiKey);
        body.put("prompt", prompt);
        body.put("width", "1024");
        body.put("height", "576");
        body.put("samples", "1");
        body.put("safety_checker", "no");
        body.put("enhance_prompt", "yes");
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        String status = "";
        String imageUrl = "";
        for (int attempt = 0; attempt < maxPollAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("图片生成已取消");
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() != 200) throw new IllegalStateException("ModelsLab 失败，HTTP " + response.statusCode());
                byte[] bytes = stream.readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("图片响应过大");
                var root = json.readTree(bytes);
                status = root.path("status").asText("");
                if (root.path("output").isArray() && !root.path("output").isEmpty()) {
                    imageUrl = root.path("output").get(0).asText("");
                }
                if (imageUrl.isBlank() && root.path("fetch_result").isArray() && !root.path("fetch_result").isEmpty()) {
                    imageUrl = root.path("fetch_result").get(0).asText("");
                }
            }
            if ("success".equalsIgnoreCase(status) && !imageUrl.isBlank()) break;
            if (!"processing".equalsIgnoreCase(status)) throw new IllegalStateException("ModelsLab 返回状态异常：" + status);
            Thread.sleep(5_000);
        }
        if (imageUrl.isBlank()) throw new IllegalStateException("ModelsLab 未返回图片地址");
        return download(imageUrl);
    }

    private Image download(String imageUrl) throws Exception {
        URI uri = URI.create(imageUrl);
        if (!allowPrivate && !RemoteDocuments.isPublicHttp(uri)) throw new IllegalArgumentException("图片地址不允许访问");
        var response = client.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("图片下载失败，HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IllegalStateException("图片文件过大");
            return new Image(bytes, "image/png");
        }
    }
}
