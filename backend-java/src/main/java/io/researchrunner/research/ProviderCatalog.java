package io.researchrunner.research;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A3-01: single source of truth for Route A provider coverage.
 *
 * <p>Status is deliberately honest: {@link Status#PROTOCOL_FOUNDATION} means the provider is
 * wired to a real adapter and covered by local protocol tests (wire format, headers, response and
 * error mapping) but has not been verified against the paid endpoint; {@link Status#NOT_MIGRATED}
 * means the Java backend must return an explicit 501 instead of silently falling back to another
 * provider.
 */
public final class ProviderCatalog {
    public enum Kind { CHAT, EMBEDDING, SEARCH, SCRAPER, IMAGE }
    public enum Status { IMPLEMENTED, PROTOCOL_FOUNDATION, NOT_MIGRATED }

    public record Entry(String provider, Kind kind, Status status) { }

    private static final Map<Kind, Map<String, Status>> CATALOG = build();

    private ProviderCatalog() { }

    public static Entry entry(Kind kind, String provider) {
        if (provider == null) return new Entry("", kind, Status.NOT_MIGRATED);
        Status status = CATALOG.getOrDefault(kind, Map.of()).get(provider);
        return new Entry(provider, kind, status == null ? Status.NOT_MIGRATED : status);
    }

    public static boolean usable(Kind kind, String provider) {
        return entry(kind, provider).status() != Status.NOT_MIGRATED;
    }

    public static void require(Kind kind, String provider) {
        Entry entry = entry(kind, provider);
        if (entry.status() == Status.NOT_MIGRATED) {
            throw new UnsupportedProviderException(kind, provider);
        }
    }

    private static Map<Kind, Map<String, Status>> build() {
        Map<Kind, Map<String, Status>> catalog = new LinkedHashMap<>();
        Map<String, Status> chat = new LinkedHashMap<>();
        for (String provider : Set.of("openai", "deepseek", "dashscope", "atlascloud", "openrouter",
                "vllm_openai", "aimlapi", "forge", "avian", "minimax", "nebius", "anthropic",
                "ollama", "groq", "mistralai", "xai", "google_genai", "azure_openai")) {
            chat.put(provider, Status.PROTOCOL_FOUNDATION);
        }
        for (String provider : Set.of("google_vertexai", "bedrock", "cohere", "fireworks",
                "huggingface", "litellm", "netmind", "together")) {
            chat.put(provider, Status.PROTOCOL_FOUNDATION);
        }
        // GigaChat needs a Sberbank OAuth handshake that is not part of the paid-provider scope.
        for (String provider : Set.of("gigachat")) {
            chat.put(provider, Status.NOT_MIGRATED);
        }
        catalog.put(Kind.CHAT, chat);

        Map<String, Status> embedding = new LinkedHashMap<>();
        for (String provider : Set.of("openai", "dashscope", "aimlapi", "minimax", "nebius",
                "openrouter", "google_genai", "ollama")) {
            embedding.put(provider, Status.PROTOCOL_FOUNDATION);
        }
        for (String provider : Set.of("azure_openai", "bedrock", "cohere", "custom", "fireworks", "gigachat",
                "google_vertexai", "huggingface", "mistralai", "netmind", "nomic",
                "together", "voyageai")) {
            embedding.put(provider, Status.NOT_MIGRATED);
        }
        catalog.put(Kind.EMBEDDING, embedding);

        Map<String, Status> search = new LinkedHashMap<>();
        search.put("tavily", Status.IMPLEMENTED);
        search.put("mcp", Status.PROTOCOL_FOUNDATION);
        search.put("brave", Status.PROTOCOL_FOUNDATION);
        search.put("bing", Status.PROTOCOL_FOUNDATION);
        search.put("searx", Status.PROTOCOL_FOUNDATION);
        search.put("serper", Status.PROTOCOL_FOUNDATION);
        search.put("google", Status.PROTOCOL_FOUNDATION);
        search.put("duckduckgo", Status.PROTOCOL_FOUNDATION);
        search.put("arxiv", Status.PROTOCOL_FOUNDATION);
        search.put("openalex", Status.PROTOCOL_FOUNDATION);
        search.put("semantic_scholar", Status.PROTOCOL_FOUNDATION);
        search.put("exa", Status.PROTOCOL_FOUNDATION);
        search.put("bocha", Status.PROTOCOL_FOUNDATION);
        search.put("pubmed_central", Status.PROTOCOL_FOUNDATION);
        search.put("serpapi", Status.PROTOCOL_FOUNDATION);
        search.put("searchapi", Status.PROTOCOL_FOUNDATION);
        search.put("groundroute", Status.PROTOCOL_FOUNDATION);
        search.put("crw", Status.PROTOCOL_FOUNDATION);
        search.put("custom", Status.PROTOCOL_FOUNDATION);
        search.put("getxapi", Status.PROTOCOL_FOUNDATION);
        search.put("xquik", Status.PROTOCOL_FOUNDATION);
        catalog.put(Kind.SEARCH, search);

        Map<String, Status> scraper = new LinkedHashMap<>();
        scraper.put("bs", Status.PROTOCOL_FOUNDATION);
        scraper.put("beautiful_soup", Status.PROTOCOL_FOUNDATION);
        scraper.put("beautifulsoup", Status.PROTOCOL_FOUNDATION);
        scraper.put("pymupdf", Status.PROTOCOL_FOUNDATION);
        scraper.put("web_base_loader", Status.PROTOCOL_FOUNDATION);
        scraper.put("arxiv", Status.PROTOCOL_FOUNDATION);
        scraper.put("firecrawl", Status.PROTOCOL_FOUNDATION);
        scraper.put("tavily_extract", Status.PROTOCOL_FOUNDATION);
        for (String provider : Set.of("browser", "nodriver")) {
            scraper.put(provider, Status.NOT_MIGRATED);
        }
        catalog.put(Kind.SCRAPER, scraper);

        Map<String, Status> image = new LinkedHashMap<>();
        image.put("google", Status.PROTOCOL_FOUNDATION);
        image.put("gemini", Status.PROTOCOL_FOUNDATION);
        image.put("modelslab", Status.PROTOCOL_FOUNDATION);
        catalog.put(Kind.IMAGE, image);
        return catalog;
    }

    /** Immutable snapshot for diagnostics and the capabilities endpoint. */
    public static Map<Kind, Map<String, String>> snapshot() {
        Map<Kind, Map<String, String>> result = new LinkedHashMap<>();
        CATALOG.forEach((kind, providers) -> {
            Map<String, String> rows = new LinkedHashMap<>();
            providers.forEach((provider, status) -> rows.put(provider, status.name().toLowerCase()));
            result.put(kind, Map.copyOf(rows));
        });
        return Map.copyOf(result);
    }
}
