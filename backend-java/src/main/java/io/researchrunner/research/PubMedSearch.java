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

/** A3-11: PubMed Central E-utilities adapter (esearch + efetch). */
public final class PubMedSearch implements SearchGateway {
    private final HttpClient client;
    private final URI searchEndpoint;
    private final URI fetchEndpoint;
    private final String apiKey;
    private final Duration timeout;

    public PubMedSearch(HttpClient client, URI searchEndpoint, URI fetchEndpoint, String apiKey, Duration timeout) {
        this.client = client;
        this.searchEndpoint = searchEndpoint;
        this.fetchEndpoint = fetchEndpoint;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("NCBI_API_KEY 未配置");
        String searchUrl = searchEndpoint + "?db=pmc&retmode=json&retmax=" + limit
                + "&term=" + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8)
                + "&api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        var searchResponse = client.send(HttpRequest.newBuilder(URI.create(searchUrl)).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (searchResponse.statusCode() != 200) throw new IllegalStateException("PubMed 搜索失败，HTTP " + searchResponse.statusCode());
        List<String> ids = new ArrayList<>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(searchResponse.body());
        for (var id : json.path("esearchresult").path("idlist")) ids.add(id.asText());
        List<Hit> hits = new ArrayList<>();
        for (String id : ids) {
            if (hits.size() >= limit) break;
            String fetchUrl = fetchEndpoint + "?db=pmc&rettype=full&retmode=xml&id="
                    + URLEncoder.encode(id, StandardCharsets.UTF_8)
                    + "&api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            var response = client.send(HttpRequest.newBuilder(URI.create(fetchUrl)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) continue;
            var document = Jsoup.parse(response.body(), "", Parser.xmlParser());
            String title = text(document.selectFirst("article-title"));
            String abstractText = text(document.selectFirst("abstract"));
            String body = text(document.selectFirst("body"));
            String content = "Title: " + title + "\n\nAbstract: " + abstractText + "\n\nBody: " + body;
            String url = "https://www.ncbi.nlm.nih.gov/pmc/articles/" + (id.startsWith("PMC") ? id : "PMC" + id) + "/";
            if (!content.isBlank()) hits.add(new Hit(url, title, content));
        }
        return SearchSupport.filter(hits, domains, limit);
    }

    private static String text(org.jsoup.nodes.Element element) {
        return element == null ? "" : element.text();
    }
}
