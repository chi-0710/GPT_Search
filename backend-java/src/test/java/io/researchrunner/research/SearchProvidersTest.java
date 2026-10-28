package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** A3-11 protocol tests for the first HTTP search adapters. */
class SearchProvidersTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void braveParsesAndFiltersByDomain() throws Exception {
        var auth = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/res/v1/web/search", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            query.set(exchange.getRequestURI().getQuery());
            String body = """
                    {"web":{"results":[
                      {"url":"https://example.org/a","title":"A","description":"ok"},
                      {"url":"https://evil.test/b","title":"B","description":"blocked"}]}}
                    """;
            respond(exchange, body);
        });
        server.start();
        try {
            var search = new BraveSearch(java.net.http.HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/res/v1/web/search"),
                    "synthetic", json, Duration.ofSeconds(5));
            var hits = search.search("问题", List.of("example.org"), 5);
            assertEquals(1, hits.size());
            assertEquals("https://example.org/a", hits.getFirst().url());
            assertEquals("synthetic", auth.get());
            assertTrue(query.get().contains("site") && query.get().contains("example.org"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bingParsesWebPagesAndSendsSubscriptionKey() throws Exception {
        var auth = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v7.0/search", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Ocp-Apim-Subscription-Key"));
            respond(exchange, """
                    {"webPages":{"value":[
                      {"url":"https://example.org/news","name":"News","snippet":"摘要"}]}}
                    """);
        });
        server.start();
        try {
            var search = new BingSearch(java.net.http.HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v7.0/search"),
                    "synthetic", json, Duration.ofSeconds(5));
            var hits = search.search("问题", List.of(), 5);
            assertEquals(1, hits.size());
            assertEquals("News", hits.getFirst().title());
            assertEquals("synthetic", auth.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searxParsesJsonAndSplitsRequestsPerDomain() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            requests.incrementAndGet();
            respond(exchange, """
                    {"results":[
                      {"url":"https://example.org/a","title":"A","content":"内容"},
                      {"url":"https://second.org/b","title":"B","content":"其他"}]}
                    """);
        });
        server.start();
        try {
            var search = new SearxSearch(java.net.http.HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"),
                    json, Duration.ofSeconds(5));
            var hits = search.search("问题", List.of("example.org", "second.org"), 5);
            assertEquals(2, requests.get());
            assertEquals(2, hits.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serperGoogleAndDuckDuckGoAdaptersParseResults() throws Exception {
        var auth = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/serper", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("X-API-KEY"));
            respond(exchange, """
                    {"organic":[{"link":"https://example.org/s","title":"S","snippet":"摘要"}]}
                    """);
        });
        server.createContext("/customsearch/v1", exchange -> respond(exchange, """
                {"items":[{"link":"https://example.org/g","title":"G","snippet":"谷歌"}]}
                """));
        server.createContext("/html/", exchange -> respond(exchange, """
                <div class="result"><a class="result__a"
                  href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fd">D</a>
                  <a class="result__snippet">DDG 摘要</a></div>
                """));
        server.start();
        try {
            int port = server.getAddress().getPort();
            var http = java.net.http.HttpClient.newHttpClient();
            var serper = new SerperSearch(http, URI.create("http://127.0.0.1:" + port + "/serper"),
                    "synthetic", json, Duration.ofSeconds(5));
            assertEquals(1, serper.search("问题", List.of(), 5).size());
            assertEquals("synthetic", auth.get());

            var google = new GoogleCseSearch(http, URI.create("http://127.0.0.1:" + port + "/customsearch/v1"),
                    "key", "cx", json, Duration.ofSeconds(5));
            assertEquals("G", google.search("问题", List.of(), 5).getFirst().title());

            var ddg = new DuckDuckGoSearch(http, URI.create("http://127.0.0.1:" + port + "/html/"),
                    Duration.ofSeconds(5));
            var hits = ddg.search("问题", List.of(), 5);
            assertEquals("https://example.org/d", hits.getFirst().url());
            assertEquals("DDG 摘要", hits.getFirst().content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void academicAdaptersParseAtomAndJson() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/query", exchange -> respond(exchange, """
                <feed xmlns="http://www.w3.org/2005/Atom"><entry>
                  <id>http://arxiv.org/abs/1234.5678v1</id>
                  <title>论文标题</title><summary>论文摘要</summary>
                  <link title="pdf" href="http://arxiv.org/pdf/1234.5678v1"/>
                </entry></feed>
                """));
        server.createContext("/works", exchange -> respond(exchange, """
                {"results":[{"id":"https://openalex.org/W1","title":"开放学术",
                 "doi":"https://doi.org/10.1/x",
                 "abstract_inverted_index":{"研究":[0],"结论":[1]}}]}
                """));
        server.createContext("/paper/search", exchange -> respond(exchange, """
                {"data":[{"title":"语义论文","url":"https://www.semanticscholar.org/paper/1",
                 "abstract":"语义摘要"}]}
                """));
        server.start();
        try {
            int port = server.getAddress().getPort();
            var http = java.net.http.HttpClient.newHttpClient();
            var arxiv = new ArxivSearch(http, URI.create("http://127.0.0.1:" + port + "/api/query"),
                    Duration.ofSeconds(5));
            var arxivHits = arxiv.search("主题", List.of(), 5);
            assertEquals("论文标题", arxivHits.getFirst().title());
            assertEquals("论文摘要", arxivHits.getFirst().content());

            var openalex = new OpenAlexSearch(http, URI.create("http://127.0.0.1:" + port + "/works"),
                    json, Duration.ofSeconds(5), null, null);
            var openAlexHits = openalex.search("主题", List.of(), 5);
            assertEquals("https://doi.org/10.1/x", openAlexHits.getFirst().url());
            assertEquals("研究 结论", openAlexHits.getFirst().content());

            var s2 = new SemanticScholarSearch(http,
                    URI.create("http://127.0.0.1:" + port + "/paper/search"), json,
                    Duration.ofSeconds(5), null);
            assertEquals("语义摘要", s2.search("主题", List.of(), 5).getFirst().content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exaAndBochaAdaptersParseResults() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/exa", exchange -> respond(exchange, """
                {"results":[{"url":"https://example.org/e","title":"E","text":"Exa 正文"}]}
                """));
        server.createContext("/bocha", exchange -> respond(exchange, """
                {"data":{"webPages":{"value":[
                  {"url":"https://example.org/b","name":"B","snippet":"Bocha 摘要"}]}}}
                """));
        server.start();
        try {
            int port = server.getAddress().getPort();
            var http = java.net.http.HttpClient.newHttpClient();
            var exa = new ExaSearch(http, URI.create("http://127.0.0.1:" + port + "/exa"),
                    "synthetic", json, Duration.ofSeconds(5));
            assertEquals("Exa 正文", exa.search("主题", List.of(), 5).getFirst().content());

            var bocha = new BochaSearch(http, URI.create("http://127.0.0.1:" + port + "/bocha"),
                    "synthetic", json, Duration.ofSeconds(5));
            assertEquals("Bocha 摘要", bocha.search("主题", List.of(), 5).getFirst().content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pubmedSearchesThenFetchesFullText() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/esearch", exchange -> respond(exchange, """
                {"esearchresult":{"idlist":["PMC123"]}}
                """));
        server.createContext("/efetch", exchange -> respond(exchange, """
                <article><article-title>医学论文</article-title>
                  <abstract><p>摘要内容</p></abstract>
                  <body><p>正文内容</p></body></article>
                """));
        server.start();
        try {
            int port = server.getAddress().getPort();
            var pubmed = new PubMedSearch(java.net.http.HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + port + "/esearch"),
                    URI.create("http://127.0.0.1:" + port + "/efetch"),
                    "synthetic", Duration.ofSeconds(5));
            var hits = pubmed.search("主题", List.of(), 5);
            assertEquals(1, hits.size());
            assertEquals("医学论文", hits.getFirst().title());
            assertTrue(hits.getFirst().content().contains("正文内容"));
            assertTrue(hits.getFirst().url().contains("PMC123"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void extraHttpProvidersParseTheirPayloads() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/serpapi", exchange -> respond(exchange,
                "{\"organic_results\":[{\"link\":\"https://example.org/s\",\"title\":\"S\",\"snippet\":\"摘要\"}]}"));
        server.createContext("/searchapi", exchange -> respond(exchange,
                "{\"organic_results\":[{\"link\":\"https://example.org/a\",\"title\":\"A\",\"snippet\":\"摘要A\"}]}"));
        server.createContext("/groundroute", exchange -> respond(exchange,
                "{\"results\":[{\"url\":\"https://example.org/g\",\"title\":\"G\",\"content\":\"正文G\"}]}"));
        server.createContext("/crw/v1/search", exchange -> respond(exchange,
                "{\"success\":true,\"data\":[{\"url\":\"https://example.org/c\",\"markdown\":\"正文C\"}]}"));
        server.start();
        try {
            int port = server.getAddress().getPort();
            var http = java.net.http.HttpClient.newHttpClient();
            assertEquals("摘要", ExtraSearchProviders.serpapi(http,
                    URI.create("http://127.0.0.1:" + port + "/serpapi"), "k", json, Duration.ofSeconds(5))
                    .search("主题", List.of(), 5).getFirst().content());
            assertEquals("摘要A", ExtraSearchProviders.searchapi(http,
                    URI.create("http://127.0.0.1:" + port + "/searchapi"), "k", json, Duration.ofSeconds(5))
                    .search("主题", List.of(), 5).getFirst().content());
            assertEquals("正文G", ExtraSearchProviders.groundroute(http,
                    URI.create("http://127.0.0.1:" + port + "/groundroute"), "k", json, Duration.ofSeconds(5))
                    .search("主题", List.of(), 5).getFirst().content());
            assertEquals("正文C", ExtraSearchProviders.crw(http,
                    URI.create("http://127.0.0.1:" + port + "/crw/v1/search"), "k", json, Duration.ofSeconds(5))
                    .search("主题", List.of(), 5).getFirst().content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void customRetrieverReadsConfiguredEndpoint() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom", exchange -> respond(exchange,
                "[{\"url\":\"https://example.org/x\",\"title\":\"X\",\"raw_content\":\"自定义正文\"}]"));
        server.start();
        try {
            var env = new MockEnvironment()
                    .withProperty("RETRIEVER_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort() + "/custom");
            var gateway = SearchProviderFactory.create("custom", env, json, Map.of());
            assertEquals("自定义正文", gateway.search("主题", List.of(), 5).getFirst().content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void factorySelectsAdaptersAndRejectsUnmigratedProviders() {
        var env = new MockEnvironment()
                .withProperty("BRAVE_API_KEY", "x")
                .withProperty("BING_API_KEY", "y");
        assertInstanceOf(BraveSearch.class, SearchProviderFactory.create("brave", env, json, Map.of()));
        assertInstanceOf(BingSearch.class, SearchProviderFactory.create("bing", env, json, Map.of()));
        assertThrows(UnsupportedProviderException.class,
                () -> SearchProviderFactory.create("unknown-search-vendor", env, json, Map.of()));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
