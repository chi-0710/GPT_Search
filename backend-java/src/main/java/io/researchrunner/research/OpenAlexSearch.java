package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
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

/** A3-11: OpenAlex works API adapter (no key required; polite-pool options supported). */
public final class OpenAlexSearch implements SearchGateway {
    private final HttpClient client;
    private final URI endpoint;
    private final ObjectMapper json;
    private final Duration timeout;
    private final String email;
    private final String apiKey;

    public OpenAlexSearch(HttpClient client, URI endpoint, ObjectMapper json, Duration timeout,
            String email, String apiKey) {
        this.client = client;
        this.endpoint = endpoint;
        this.json = json;
        this.timeout = timeout;
        this.email = email;
        this.apiKey = apiKey;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
        StringBuilder url = new StringBuilder(endpoint.toString())
                .append("?search=").append(URLEncoder.encode(query.strip(), StandardCharsets.UTF_8))
                .append("&per_page=").append(Math.min(25, limit))
                .append("&sort=relevance_score:desc");
        if (email != null && !email.isBlank()) url.append("&mailto=").append(URLEncoder.encode(email, StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) url.append("&api_key=").append(URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout)
                .header("Accept", "application/json").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("OpenAlex 搜索失败，HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("搜索响应过大");
            List<Hit> hits = new ArrayList<>();
            for (var item : json.readTree(bytes).path("results")) {
                String href = pickHref(item);
                if (href.isBlank()) continue;
                String body = reconstruct(item.path("abstract_inverted_index"));
                hits.add(new Hit(href, item.path("title").asText("No Title"),
                        body.isBlank() ? "Abstract not available" : body));
            }
            return SearchSupport.filter(hits, domains, limit);
        }
    }

    private static String pickHref(JsonNode item) {
        for (String pointer : List.of("/primary_location/landing_page_url", "/doi", "/id")) {
            String value = item.at(pointer).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    /** Rebuilds the abstract from OpenAlex's {word: [positions]} inverted index. */
    static String reconstruct(JsonNode inverted) {
        if (inverted == null || !inverted.isObject()) return "";
        java.util.TreeMap<Integer, String> words = new java.util.TreeMap<>();
        inverted.fields().forEachRemaining(entry -> {
            for (var position : entry.getValue()) words.put(position.asInt(), entry.getKey());
        });
        return String.join(" ", words.values());
    }
}
