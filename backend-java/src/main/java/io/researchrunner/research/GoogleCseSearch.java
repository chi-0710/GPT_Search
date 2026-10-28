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

/** A3-11: Google Programmable Search (Custom Search JSON API). */
public final class GoogleCseSearch implements SearchGateway {
    private final HttpClient client;
    private final URI endpoint;
    private final String key;
    private final String cx;
    private final ObjectMapper json;
    private final Duration timeout;

    public GoogleCseSearch(HttpClient client, URI endpoint, String key, String cx, ObjectMapper json, Duration timeout) {
        this.client = client;
        this.endpoint = endpoint;
        this.key = key;
        this.cx = cx;
        this.json = json;
        this.timeout = timeout;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
        if (key == null || key.isBlank()) throw new IllegalStateException("GOOGLE_API_KEY 未配置");
        if (cx == null || cx.isBlank()) throw new IllegalStateException("GOOGLE_CX_KEY 未配置");
        String q = SearchSupport.withDomains(query.strip(), domains);
        String url = endpoint + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&cx=" + URLEncoder.encode(cx, StandardCharsets.UTF_8)
                + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&num=" + Math.min(10, limit);
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Accept", "application/json").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("Google 搜索失败，HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("搜索响应过大");
            List<Hit> hits = new ArrayList<>();
            for (var item : json.readTree(bytes).path("items")) {
                hits.add(new Hit(item.path("link").asText(""), item.path("title").asText(""),
                        item.path("snippet").asText("")));
            }
            return SearchSupport.filter(hits, domains, limit);
        }
    }
}
