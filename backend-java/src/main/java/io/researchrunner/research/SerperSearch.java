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

/** A3-11: Serper.dev Google search adapter. */
public final class SerperSearch implements SearchGateway {
    private final HttpClient client;
    private final URI endpoint;
    private final String key;
    private final ObjectMapper json;
    private final Duration timeout;

    public SerperSearch(HttpClient client, URI endpoint, String key, ObjectMapper json, Duration timeout) {
        this.client = client;
        this.endpoint = endpoint;
        this.key = key;
        this.json = json;
        this.timeout = timeout;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
        if (key == null || key.isBlank()) throw new IllegalStateException("SERPER_API_KEY 未配置");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", SearchSupport.withDomains(query.strip(), domains));
        body.put("num", limit);
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("X-API-KEY", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("Serper 搜索失败，HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("搜索响应过大");
            List<Hit> hits = new ArrayList<>();
            for (var item : json.readTree(bytes).path("organic")) {
                hits.add(new Hit(item.path("link").asText(""), item.path("title").asText(""),
                        item.path("snippet").asText("")));
            }
            return SearchSupport.filter(hits, domains, limit);
        }
    }
}
