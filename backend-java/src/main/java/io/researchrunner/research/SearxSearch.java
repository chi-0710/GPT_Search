package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A3-11: SearXNG JSON API adapter. */
public final class SearxSearch implements SearchGateway {
    private final HttpClient client;
    private final URI endpoint;
    private final ObjectMapper json;
    private final Duration timeout;

    public SearxSearch(HttpClient client, URI endpoint, ObjectMapper json, Duration timeout) {
        this.client = client;
        this.endpoint = endpoint;
        this.json = json;
        this.timeout = timeout;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
        // Python sends one request per domain for engines without OR support; mirror that.
        List<String> queries = new ArrayList<>();
        if (domains == null || domains.isEmpty()) {
            queries.add(query.strip());
        } else {
            for (String domain : domains) {
                String host = DomainPolicy.hostname(domain, true);
                if (host != null) queries.add(query.strip() + " site:" + host);
            }
        }
        Map<String, Hit> merged = new LinkedHashMap<>();
        for (String q : queries) {
            for (Hit hit : searchOnce(q, limit)) {
                if (!merged.containsKey(hit.url())) merged.put(hit.url(), hit);
                if (merged.size() >= limit) break;
            }
            if (merged.size() >= limit) break;
        }
        return SearchSupport.filter(List.copyOf(merged.values()), domains, limit);
    }

    private List<Hit> searchOnce(String query, int limit) throws Exception {
        String url = endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&safesearch=0";
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Accept", "application/json").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("SearXNG 搜索失败，HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("搜索响应过大");
            var root = json.readTree(bytes);
            List<Hit> hits = new ArrayList<>();
            for (var item : root.path("results")) {
                String urlValue = item.path("url").asText("");
                if (urlValue.isBlank()) continue;
                hits.add(new Hit(urlValue, item.path("title").asText(""), item.path("content").asText("")));
            }
            return hits;
        }
    }
}
