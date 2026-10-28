package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** A3-12 protocol tests for Firecrawl / Tavily Extract and the SCRAPER router. */
class ScrapersTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void firecrawlReturnsMarkdown() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scrape", exchange -> respond(exchange, """
                {"success":true,"data":{"markdown":"# 标题\\n\\n正文","metadata":{"title":"页面"}}}
                """));
        server.start();
        try {
            var scraper = new FirecrawlScraper(java.net.http.HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/scrape"),
                    "synthetic", json, Duration.ofSeconds(5));
            var hit = scraper.fetch("https://example.org/a", List.of());
            assertEquals("页面", hit.title());
            assertEquals("# 标题\n\n正文", hit.content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tavilyExtractReturnsRawContent() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/extract", exchange -> respond(exchange, """
                {"results":[{"url":"https://example.org/a","title":"A","raw_content":"提取正文"}]}
                """));
        server.start();
        try {
            var scraper = new TavilyExtractScraper(java.net.http.HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/extract"),
                    "synthetic", json, Duration.ofSeconds(5));
            assertEquals("提取正文", scraper.fetch("https://example.org/a", List.of()).content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void factoryRoutesAliasesAndRejectsBrowserAutomation() {
        var env = new MockEnvironment().withProperty("FIRECRAWL_API_KEY", "x");
        org.junit.jupiter.api.Assertions.assertNotNull(ScraperFactory.create("bs", env, json));
        org.junit.jupiter.api.Assertions.assertNotNull(ScraperFactory.create("web_base_loader", env, json));
        assertInstanceOf(FirecrawlScraper.class, ScraperFactory.create("firecrawl", env, json));
        assertThrows(UnsupportedProviderException.class, () -> ScraperFactory.create("browser", env, json));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
