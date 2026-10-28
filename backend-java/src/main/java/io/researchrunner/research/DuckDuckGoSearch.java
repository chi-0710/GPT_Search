package io.researchrunner.research;

import java.net.URI;
import java.net.URLDecoder;
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
import org.jsoup.Jsoup;

/**
 * A3-11: DuckDuckGo HTML endpoint adapter.
 *
 * <p>Python uses the third-party {@code ddgs} package; the JVM path parses the public HTML
 * result page. One request per allowed domain mirrors the Python retriever's domain handling.
 */
public final class DuckDuckGoSearch implements SearchGateway {
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    private final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

    public DuckDuckGoSearch(HttpClient client, URI endpoint, Duration timeout) {
        this.client = client;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
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
            for (Hit hit : searchOnce(q)) {
                merged.putIfAbsent(hit.url(), hit);
                if (merged.size() >= limit) break;
            }
            if (merged.size() >= limit) break;
        }
        return SearchSupport.filter(List.copyOf(merged.values()), domains, limit);
    }

    private List<Hit> searchOnce(String query) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IllegalStateException("DuckDuckGo 搜索失败，HTTP " + response.statusCode());
        var document = Jsoup.parse(response.body(), endpoint.toString());
        List<Hit> hits = new ArrayList<>();
        for (var link : document.select("a.result__a")) {
            String href = decodeRedirect(link.attr("href"));
            if (href == null || href.isBlank()) continue;
            var parent = link.closest(".result");
            String snippet = parent == null ? "" : parent.selectFirst(".result__snippet") == null
                    ? "" : parent.selectFirst(".result__snippet").text();
            hits.add(new Hit(href, link.text(), snippet));
        }
        return hits;
    }

    static String decodeRedirect(String href) {
        if (href == null || href.isBlank()) return null;
        String normalized = href.startsWith("//") ? "https:" + href : href;
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (host != null && host.endsWith("duckduckgo.com") && uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&")) {
                    if (parameter.startsWith("uddg=")) {
                        return URLDecoder.decode(parameter.substring("uddg=".length()), StandardCharsets.UTF_8);
                    }
                }
                return null;
            }
            return normalized;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
