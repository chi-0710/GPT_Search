package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;

/** A3-11: selects the configured search adapter; unknown providers fail with HTTP 501. */
public final class SearchProviderFactory {
    private static final Duration TIMEOUT = Duration.ofSeconds(100);
    /** Python's X/Twitter retrievers use a 15s socket timeout. */
    private static final Duration SOCIAL_TIMEOUT = Duration.ofSeconds(15);

    private SearchProviderFactory() { }

    public static SearchGateway create(String retriever, Environment env, ObjectMapper json,
            Map<String, String> headers) {
        HttpClient client = HttpClient.newHttpClient();
        String provider = retriever == null || retriever.isBlank() ? "tavily" : retriever.strip();
        return switch (provider) {
            case "tavily" -> new TavilySearch(client,
                    URI.create(env.getProperty("TAVILY_BASE_URL", "https://api.tavily.com/search")),
                    headers.getOrDefault("tavily_api_key", env.getProperty("TAVILY_API_KEY")),
                    json, TIMEOUT);
            case "brave" -> new BraveSearch(client,
                    URI.create(env.getProperty("BRAVE_BASE_URL", "https://api.search.brave.com/res/v1/web/search")),
                    headers.getOrDefault("brave_api_key", env.getProperty("BRAVE_API_KEY")),
                    json, TIMEOUT);
            case "bing" -> new BingSearch(client,
                    URI.create(env.getProperty("BING_BASE_URL", "https://api.bing.microsoft.com/v7.0/search")),
                    headers.getOrDefault("bing_api_key", env.getProperty("BING_API_KEY")),
                    json, TIMEOUT);
            case "searx" -> new SearxSearch(client,
                    URI.create(env.getProperty("SEARXNG_BASE_URL",
                            env.getProperty("SEARX_BASE_URL", "http://localhost:8888")) + "/search"),
                    json, TIMEOUT);
            case "serper" -> new SerperSearch(client,
                    URI.create(env.getProperty("SERPER_BASE_URL", "https://google.serper.dev/search")),
                    headers.getOrDefault("serper_api_key", env.getProperty("SERPER_API_KEY")),
                    json, TIMEOUT);
            case "google" -> new GoogleCseSearch(client,
                    URI.create(env.getProperty("GOOGLE_CSE_BASE_URL",
                            "https://www.googleapis.com/customsearch/v1")),
                    headers.getOrDefault("google_api_key", env.getProperty("GOOGLE_API_KEY")),
                    headers.getOrDefault("google_cx_key", env.getProperty("GOOGLE_CX_KEY")),
                    json, TIMEOUT);
            case "duckduckgo" -> new DuckDuckGoSearch(client,
                    URI.create(env.getProperty("DUCKDUCKGO_BASE_URL", "https://html.duckduckgo.com/html/")),
                    TIMEOUT);
            case "arxiv" -> new ArxivSearch(client,
                    URI.create(env.getProperty("ARXIV_BASE_URL", "https://export.arxiv.org/api/query")),
                    TIMEOUT);
            case "openalex" -> new OpenAlexSearch(client,
                    URI.create(env.getProperty("OPENALEX_BASE_URL", "https://api.openalex.org/works")),
                    json, TIMEOUT, env.getProperty("OPENALEX_EMAIL"), env.getProperty("OPENALEX_API_KEY"));
            case "semantic_scholar" -> new SemanticScholarSearch(client,
                    URI.create(env.getProperty("SEMANTIC_SCHOLAR_BASE_URL",
                            "https://api.semanticscholar.org/graph/v1/paper/search")),
                    json, TIMEOUT, env.getProperty("SEMANTIC_SCHOLAR_API_KEY"));
            case "exa" -> new ExaSearch(client,
                    URI.create(env.getProperty("EXA_BASE_URL", "https://api.exa.ai/search")),
                    headers.getOrDefault("exa_api_key", env.getProperty("EXA_API_KEY")),
                    json, TIMEOUT);
            case "bocha" -> new BochaSearch(client,
                    URI.create(env.getProperty("BOCHA_BASE_URL", "https://api.bochaai.com/v1/web-search")),
                    headers.getOrDefault("bocha_api_key", env.getProperty("BOCHA_API_KEY")),
                    json, TIMEOUT);
            case "pubmed_central" -> new PubMedSearch(client,
                    URI.create(env.getProperty("PUBMED_SEARCH_URL",
                            "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi")),
                    URI.create(env.getProperty("PUBMED_FETCH_URL",
                            "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi")),
                    env.getProperty("NCBI_API_KEY"), TIMEOUT);
            case "serpapi" -> ExtraSearchProviders.serpapi(client,
                    URI.create(env.getProperty("SERPAPI_BASE_URL", "https://serpapi.com/search.json")),
                    headers.getOrDefault("serpapi_api_key", env.getProperty("SERPAPI_API_KEY")), json, TIMEOUT);
            case "searchapi" -> ExtraSearchProviders.searchapi(client,
                    URI.create(env.getProperty("SEARCHAPI_BASE_URL", "https://www.searchapi.io/api/v1/search")),
                    headers.getOrDefault("searchapi_api_key", env.getProperty("SEARCHAPI_API_KEY")), json, TIMEOUT);
            case "groundroute" -> ExtraSearchProviders.groundroute(client,
                    URI.create(env.getProperty("GROUNDROUTE_BASE_URL", "https://api.groundroute.ai/v1/search")),
                    headers.getOrDefault("groundroute_api_key", env.getProperty("GROUNDROUTE_API_KEY")), json, TIMEOUT);
            case "crw" -> ExtraSearchProviders.crw(client,
                    URI.create(env.getProperty("CRW_API_URL", "https://fastcrw.com/api").replaceAll("/+$", "") + "/v1/search"),
                    headers.getOrDefault("crw_api_key", env.getProperty("CRW_API_KEY")), json, TIMEOUT);
            case "getxapi" -> SocialSearchProviders.getxapi(client,
                    URI.create(env.getProperty("GETXAPI_BASE_URL",
                            "https://api.getxapi.com/twitter/tweet/advanced_search")),
                    headers.getOrDefault("getxapi_api_key", env.getProperty("GETXAPI_API_KEY")),
                    json, SOCIAL_TIMEOUT);
            case "xquik" -> SocialSearchProviders.xquik(client,
                    URI.create(env.getProperty("XQUIK_BASE_URL",
                            "https://xquik.com/api/v1/x/tweets/search")),
                    headers.getOrDefault("xquik_api_key", env.getProperty("XQUIK_API_KEY")),
                    json, SOCIAL_TIMEOUT);
            case "mcp" -> none();
            case "custom" -> customSearch(env, json);
            default -> throw new UnsupportedProviderException(ProviderCatalog.Kind.SEARCH, provider);
        };
    }

    /** MCP research supplies its own collector, so the HTTP gateway is intentionally a no-op. */
    public static SearchGateway none() {
        return (query, domains, limit) -> List.of();
    }

    /** Custom retriever: GET RETRIEVER_ENDPOINT?query=... returning [{url,raw_content}]. */
    private static SearchGateway customSearch(Environment env, ObjectMapper json) {
        String endpoint = env.getProperty("RETRIEVER_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) throw new IllegalStateException("RETRIEVER_ENDPOINT 未配置");
        var client = HttpClient.newHttpClient();
        return (query, domains, limit) -> {
            String url = endpoint + (endpoint.contains("?") ? "&" : "?")
                    + "query=" + java.net.URLEncoder.encode(query.strip(), java.nio.charset.StandardCharsets.UTF_8);
            var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() != 200) throw new IllegalStateException("自定义检索失败，HTTP " + response.statusCode());
                byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
                if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("自定义检索响应过大");
                var root = json.readTree(bytes);
                if (!root.isArray()) throw new IllegalStateException("自定义检索响应必须是 JSON 数组");
                List<SearchGateway.Hit> hits = new java.util.ArrayList<>();
                for (var item : root) {
                    String href = item.path("url").asText("");
                    if (!href.isBlank()) hits.add(new SearchGateway.Hit(href,
                            item.path("title").asText(""), item.path("raw_content").asText("")));
                }
                return SearchSupport.filter(hits, domains, limit);
            }
        };
    }
}
