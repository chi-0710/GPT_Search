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
import java.util.List;

/** A3-11: Semantic Scholar Graph API adapter. */
public final class SemanticScholarSearch implements SearchGateway {
    private final HttpClient client;
    private final URI endpoint;
    private final ObjectMapper json;
    private final Duration timeout;
    private final String apiKey;

    public SemanticScholarSearch(HttpClient client, URI endpoint, ObjectMapper json, Duration timeout, String apiKey) {
        this.client = client;
        this.endpoint = endpoint;
        this.json = json;
        this.timeout = timeout;
        this.apiKey = apiKey;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
        String url = endpoint + "?query=" + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8)
                + "&limit=" + Math.min(100, limit) + "&fields=title,url,abstract";
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) builder.header("x-api-key", apiKey);
        var response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Semantic Scholar 搜索失败，HTTP " + response.statusCode());
            }
            byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("搜索响应过大");
            List<Hit> hits = new ArrayList<>();
            for (var item : json.readTree(bytes).path("data")) {
                String urlValue = item.path("url").asText("");
                if (urlValue.isBlank()) continue;
                hits.add(new Hit(urlValue, item.path("title").asText(""),
                        item.path("abstract").asText("")));
            }
            return SearchSupport.filter(hits, domains, limit);
        }
    }
}
