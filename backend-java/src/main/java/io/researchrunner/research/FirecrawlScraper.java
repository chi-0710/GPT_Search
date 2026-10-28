package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A3-12: Firecrawl scrape adapter; returns Markdown as evidence text. */
public final class FirecrawlScraper implements SourceCollector.Scraper {
    private final HttpClient client;
    private final URI endpoint;
    private final String key;
    private final ObjectMapper json;
    private final Duration timeout;

    public FirecrawlScraper(HttpClient client, URI endpoint, String key, ObjectMapper json, Duration timeout) {
        this.client = client;
        this.endpoint = endpoint;
        this.key = key;
        this.json = json;
        this.timeout = timeout;
    }

    @Override public SearchGateway.Hit fetch(String url, List<String> domains) throws Exception {
        if (key == null || key.isBlank()) throw new IllegalStateException("FIRECRAWL_API_KEY 未配置");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("formats", List.of("markdown"));
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("Firecrawl 抓取失败，HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("抓取响应过大");
            var root = json.readTree(bytes);
            if (!root.path("success").asBoolean(false)) throw new IllegalStateException("Firecrawl 未返回成功结果");
            var data = root.path("data");
            String content = data.path("markdown").asText("");
            String title = data.path("metadata").path("title").asText(url);
            if (content.isBlank()) throw new IllegalStateException("Firecrawl 未返回正文");
            return new SearchGateway.Hit(url, title, content);
        }
    }
}
