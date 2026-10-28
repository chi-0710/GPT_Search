package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A3-11: Exa search API adapter (neural/keyword). */
public final class ExaSearch implements SearchGateway {
    private final HttpClient client;
    private final URI endpoint;
    private final String key;
    private final ObjectMapper json;
    private final Duration timeout;

    public ExaSearch(HttpClient client, URI endpoint, String key, ObjectMapper json, Duration timeout) {
        this.client = client;
        this.endpoint = endpoint;
        this.key = key;
        this.json = json;
        this.timeout = timeout;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
        if (key == null || key.isBlank()) throw new IllegalStateException("EXA_API_KEY 未配置");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query.strip());
        body.put("type", "auto");
        body.put("numResults", limit);
        body.put("contents", Map.of("text", Map.of("maxCharacters", 2000)));
        if (domains != null && !domains.isEmpty()) body.put("includeDomains", domains);
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("x-api-key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("Exa 搜索失败，HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("搜索响应过大");
            List<Hit> hits = new ArrayList<>();
            for (var item : json.readTree(bytes).path("results")) {
                String url = item.path("url").asText("");
                if (url.isBlank()) continue;
                String text = item.path("text").asText("");
                if (text.isBlank()) text = item.path("summary").asText("");
                hits.add(new Hit(url, item.path("title").asText(""), text));
            }
            return SearchSupport.filter(hits, domains, limit);
        }
    }
}
