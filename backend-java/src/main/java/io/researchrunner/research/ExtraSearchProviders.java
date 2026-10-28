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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A3-11: remaining HTTP search providers kept in one place to avoid adapter sprawl. */
public final class ExtraSearchProviders {
    private ExtraSearchProviders() { }

    public static SearchGateway serpapi(HttpClient client, URI endpoint, String key, ObjectMapper json, Duration timeout) {
        return (query, domains, limit) -> {
            if (key == null || key.isBlank()) throw new IllegalStateException("SERPAPI_API_KEY 未配置");
            String url = endpoint + "?q=" + enc(SearchSupport.withDomains(query.strip(), domains))
                    + "&num=" + limit + "&api_key=" + enc(key);
            var root = getJson(client, URI.create(url), timeout, null, json);
            return SearchSupport.filter(organic(root, limit), domains, limit);
        };
    }

    public static SearchGateway searchapi(HttpClient client, URI endpoint, String key, ObjectMapper json, Duration timeout) {
        return (query, domains, limit) -> {
            if (key == null || key.isBlank()) throw new IllegalStateException("SEARCHAPI_API_KEY 未配置");
            String url = endpoint + "?q=" + enc(SearchSupport.withDomains(query.strip(), domains)) + "&engine=google";
            var root = getJson(client, URI.create(url), timeout, Map.of("Authorization", "Bearer " + key), json);
            return SearchSupport.filter(organic(root, limit), domains, limit);
        };
    }

    public static SearchGateway groundroute(HttpClient client, URI endpoint, String key, ObjectMapper json, Duration timeout) {
        return (query, domains, limit) -> {
            if (key == null || key.isBlank()) throw new IllegalStateException("GROUNDROUTE_API_KEY 未配置");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", SearchSupport.withDomains(query.strip(), domains));
            body.put("max_results", limit);
            var root = postJson(client, endpoint, timeout, Map.of("Authorization", "Bearer " + key), body, json);
            List<SearchGateway.Hit> hits = new ArrayList<>();
            JsonNode results = root.isArray() ? root : root.path("results");
            for (var item : results) {
                String url = firstText(item, "url", "href", "link");
                if (url.isBlank()) continue;
                hits.add(new SearchGateway.Hit(url, item.path("title").asText(""),
                        firstText(item, "content", "snippet", "body")));
                if (hits.size() >= limit) break;
            }
            return SearchSupport.filter(hits, domains, limit);
        };
    }

    public static SearchGateway crw(HttpClient client, URI endpoint, String key, ObjectMapper json, Duration timeout) {
        return (query, domains, limit) -> {
            var headers = new LinkedHashMap<String, String>();
            if (key != null && !key.isBlank()) headers.put("Authorization", "Bearer " + key);
            var root = postJson(client, endpoint, timeout, headers, Map.<String, Object>of("query", query.strip(), "limit", limit), json);
            if (root.path("success").isBoolean() && !root.path("success").asBoolean()) {
                throw new IllegalStateException("fastCRW 搜索失败");
            }
            List<SearchGateway.Hit> hits = new ArrayList<>();
            for (var item : root.path("data")) {
                String url = item.path("url").asText("");
                if (url.isBlank()) continue;
                String content = item.path("markdown").asText("");
                if (content.isBlank()) content = item.path("description").asText("");
                hits.add(new SearchGateway.Hit(url, item.path("title").asText(""), content));
                if (hits.size() >= limit) break;
            }
            return SearchSupport.filter(hits, domains, limit);
        };
    }

    private static List<SearchGateway.Hit> organic(JsonNode root, int limit) {
        List<SearchGateway.Hit> hits = new ArrayList<>();
        for (var item : root.path("organic_results")) {
            String link = item.path("link").asText("");
            if (link.isBlank() || link.contains("youtube.com")) continue;
            hits.add(new SearchGateway.Hit(link, item.path("title").asText(""), item.path("snippet").asText("")));
            if (hits.size() >= limit) break;
        }
        return hits;
    }

    private static JsonNode getJson(HttpClient client, URI uri, Duration timeout,
            Map<String, String> headers, ObjectMapper json) throws Exception {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
        if (headers != null) headers.forEach(builder::header);
        var response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        return read(response, json);
    }

    private static JsonNode postJson(HttpClient client, URI uri, Duration timeout,
            Map<String, String> headers, Map<String, Object> body, ObjectMapper json) throws Exception {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return read(response, json);
    }

    private static JsonNode read(HttpResponse<java.io.InputStream> response, ObjectMapper json) throws Exception {
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("搜索失败，HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("搜索响应过大");
            return json.readTree(bytes);
        }
    }

    private static String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = item.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
