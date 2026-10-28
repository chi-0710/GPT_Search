package io.researchrunner.research;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/** A3-11: arXiv Atom API adapter. */
public final class ArxivSearch implements SearchGateway {
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public ArxivSearch(HttpClient client, URI endpoint, Duration timeout) {
        this.client = client;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
        String url = endpoint + "?search_query=all:" + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8)
                + "&start=0&max_results=" + Math.min(50, limit);
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IllegalStateException("arXiv 搜索失败，HTTP " + response.statusCode());
        var document = Jsoup.parse(response.body(), "", Parser.xmlParser());
        List<Hit> hits = new ArrayList<>();
        for (var entry : document.select("feed > entry")) {
            String title = entry.selectFirst("title") == null ? "" : entry.selectFirst("title").text();
            String summary = entry.selectFirst("summary") == null ? "" : entry.selectFirst("summary").text();
            var pdf = entry.selectFirst("link[title=pdf]");
            String link = pdf != null ? pdf.attr("href") : entry.selectFirst("id") == null ? "" : entry.selectFirst("id").text();
            if (!link.isBlank()) hits.add(new Hit(link, title, summary));
        }
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }
}
