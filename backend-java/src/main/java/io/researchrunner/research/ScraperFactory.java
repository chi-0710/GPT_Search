package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/** A3-12: selects the configured scraper; browser automation remains an explicit 501. */
public final class ScraperFactory {
    private static final Set<String> JSOUP_ALIASES = Set.of("bs", "beautiful_soup", "beautifulsoup",
            "web_base_loader", "arxiv", "pymupdf");
    private static final Set<String> REMOTE = Set.of("firecrawl", "tavily_extract");

    private ScraperFactory() { }

    public static String normalize(String name) {
        return name == null || name.isBlank() ? "bs" : name.strip().toLowerCase(Locale.ROOT);
    }

    public static void requireSupported(String name) {
        String provider = normalize(name);
        if (!JSOUP_ALIASES.contains(provider) && !REMOTE.contains(provider)) {
            throw new UnsupportedProviderException(ProviderCatalog.Kind.SCRAPER, provider);
        }
    }

    public static SourceCollector.Scraper create(String name, Environment env, ObjectMapper json) {
        String provider = normalize(name);
        requireSupported(provider);
        if (JSOUP_ALIASES.contains(provider)) {
            var web = new WebScraper(Duration.ofSeconds(60), 8 * 1024 * 1024);
            return (url, domains) -> web.fetch(url, domains);
        }
        HttpClient client = HttpClient.newHttpClient();
        if (provider.equals("firecrawl")) {
            return new FirecrawlScraper(client,
                    URI.create(env.getProperty("FIRECRAWL_BASE_URL", "https://api.firecrawl.dev/v1/scrape")),
                    env.getProperty("FIRECRAWL_API_KEY"), json, Duration.ofSeconds(100));
        }
        return new TavilyExtractScraper(client,
                URI.create(env.getProperty("TAVILY_EXTRACT_BASE_URL", "https://api.tavily.com/extract")),
                env.getProperty("TAVILY_API_KEY"), json, Duration.ofSeconds(100));
    }
}
