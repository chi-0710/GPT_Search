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

/**
 * A3-11: X/Twitter search adapters (GetXAPI and Xquik).
 *
 * <p>Both ports follow the Python retrievers in {@code search/retrievers/getxapi/getxapi.py} and
 * {@code search/retrievers/xquik/xquik.py}: a single GET request with a short timeout, tolerant
 * parsing of {@code tweets}/{@code data} payloads (explicit JSON {@code null} is treated like a
 * missing field) and mapping to the shared {@code {title, href, body}} hit shape.
 */
final class SocialSearchProviders {
    private SocialSearchProviders() { }

    /** GetXAPI advanced tweet search: {@code GET ?q=} with a Bearer token. */
    static SearchGateway getxapi(HttpClient client, URI endpoint, String key, ObjectMapper json,
            Duration timeout) {
        return (query, domains, limit) -> {
            validate(query, limit);
            if (key == null || key.isBlank()) throw new IllegalStateException("GETXAPI_API_KEY 未配置");
            String url = endpoint + (endpoint.getQuery() == null ? "?" : "&") + "q=" + enc(query.strip());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + key);
            headers.put("Accept", "application/json");
            headers.put("User-Agent", "gpt-researcher/1.0");
            JsonNode root = getJson(client, URI.create(url), timeout, headers, json);
            JsonNode tweets = arrayOrEmpty(root.get("tweets"));
            if (tweets.isEmpty()) tweets = arrayOrEmpty(root.get("data"));

            List<SearchGateway.Hit> hits = new ArrayList<>();
            for (var tweet : tweets) {
                if (hits.size() >= limit) break;
                JsonNode author = objectOrEmpty(tweet.get("author"));
                String username = firstNonBlank(
                        text(author, "userName"), text(author, "username"), text(author, "screen_name"),
                        text(tweet, "username"), "unknown");
                String body = firstNonBlank(text(tweet, "text"), text(tweet, "full_text"));
                String id = firstNonBlank(text(tweet, "id"), text(tweet, "id_str"));
                long likes = firstNumber(tweet, "likeCount", "favorite_count");
                long retweets = firstNumber(tweet, "retweetCount", "retweet_count");
                long replies = firstNumber(tweet, "replyCount", "reply_count");
                long views = firstNumber(tweet, "viewCount", "view_count");
                String title = "@" + username + ": " + truncate(body, 80);
                hits.add(new SearchGateway.Hit("https://x.com/" + username + "/status/" + id, title,
                        body + "\n\n[likes:" + likes + " retweets:" + retweets
                                + " replies:" + replies + " views:" + views + "]"));
            }
            return SearchSupport.filter(hits, domains, limit);
        };
    }

    /** Xquik tweet search: {@code GET ?q=&limit=&queryType=Top} with an {@code X-API-Key}. */
    static SearchGateway xquik(HttpClient client, URI endpoint, String key, ObjectMapper json,
            Duration timeout) {
        return (query, domains, limit) -> {
            validate(query, limit);
            if (key == null || key.isBlank()) throw new IllegalStateException("XQUIK_API_KEY 未配置");
            int capped = Math.min(limit, 200);
            String url = endpoint + (endpoint.getQuery() == null ? "?" : "&")
                    + "q=" + enc(query.strip()) + "&limit=" + capped + "&queryType=Top";
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-API-Key", key);
            headers.put("Accept", "application/json");
            headers.put("User-Agent", "gpt-researcher/1.0");
            JsonNode root = getJson(client, URI.create(url), timeout, headers, json);
            JsonNode tweets = arrayOrEmpty(root.get("tweets"));

            List<SearchGateway.Hit> hits = new ArrayList<>();
            for (var tweet : tweets) {
                if (hits.size() >= limit) break;
                JsonNode author = objectOrEmpty(tweet.get("author"));
                String username = firstNonBlank(text(author, "username"), "unknown");
                String body = text(tweet, "text");
                String id = text(tweet, "id");
                long likes = firstNumber(tweet, "likeCount");
                long retweets = firstNumber(tweet, "retweetCount");
                long views = firstNumber(tweet, "viewCount");
                String engagement = likes + " likes, " + retweets + " RTs";
                if (views != 0) engagement += ", " + views + " views";
                hits.add(new SearchGateway.Hit("https://x.com/" + username + "/status/" + id,
                        "@" + username + ": " + truncate(body, 120),
                        body + "\n\n[" + engagement + "]"));
            }
            return SearchSupport.filter(hits, domains, limit);
        };
    }

    private static void validate(String query, int limit) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("搜索参数无效");
        if (limit < 1) throw new IllegalArgumentException("搜索参数无效");
    }

    /** Mirrors Python's {@code data.get("tweets") or data.get("data") or []}. */
    private static JsonNode arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray() ? node : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    private static JsonNode objectOrEmpty(JsonNode node) {
        return node != null && node.isObject() ? node : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    /** Returns the text value, treating an explicit JSON null exactly like a missing field. */
    private static String text(JsonNode object, String field) {
        if (object == null) return "";
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return "";
        return value.asText("");
    }

    private static long firstNumber(JsonNode object, String... fields) {
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value == null || value.isNull()) continue;
            if (value.isNumber()) return value.asLong();
            if (value.isTextual() && !value.asText().isBlank()) {
                try {
                    return Long.parseLong(value.asText().strip());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode getJson(HttpClient client, URI uri, Duration timeout,
            Map<String, String> headers, ObjectMapper json) throws Exception {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        headers.forEach(builder::header);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("X/Twitter 检索失败，HTTP " + response.statusCode());
            }
            byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("检索响应过大");
            return json.readTree(bytes);
        }
    }
}
