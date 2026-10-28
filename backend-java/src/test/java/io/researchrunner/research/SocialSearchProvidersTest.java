package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** A3-11: local protocol tests for the GetXAPI/Xquik X/Twitter retrievers (no real endpoints). */
class SocialSearchProvidersTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void getxapiMapsTweetsAndToleratesJsonNulls() throws Exception {
        var authorization = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/twitter/tweet/advanced_search", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"tweets":[
                      {"author":{"userName":"alice"},"text":"hello world","id":"123",
                       "likeCount":5,"retweetCount":2,"replyCount":1,"viewCount":100},
                      {"author":null,"text":null,"full_text":"fallback text","id":null,"id_str":"456",
                       "likeCount":null,"favorite_count":3},
                      {"author":{"screen_name":"bob"},"full_text":"%s","id_str":"789"}
                    ]}
                    """.formatted("很长".repeat(60)));
        });
        server.start();
        try {
            var gateway = new SocialSearchProvidersAccess().getxapi(json, server, "synthetic");
            var hits = gateway.search("AI 研究", List.of(), 10);
            assertEquals(3, hits.size());
            assertEquals("Bearer synthetic", authorization.get());
            assertTrue(query.get().contains("q=AI 研究"));

            assertEquals("@alice: hello world", hits.get(0).title());
            assertEquals("https://x.com/alice/status/123", hits.get(0).url());
            assertEquals("hello world\n\n[likes:5 retweets:2 replies:1 views:100]", hits.get(0).content());

            assertEquals("@unknown: fallback text", hits.get(1).title());
            assertEquals("https://x.com/unknown/status/456", hits.get(1).url());
            assertTrue(hits.get(1).content().contains("[likes:3 retweets:0 replies:0 views:0]"));
            assertTrue(hits.get(1).content().startsWith("fallback text"));

            assertTrue(hits.get(2).title().endsWith("..."));
            assertTrue(hits.get(2).title().length() <= "@bob: ".length() + 80 + 3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getxapiFallsBackToDataArrayWhenTweetsIsEmptyOrMissing() throws Exception {
        var payload = new AtomicReference<>(
                "{\"tweets\":[],\"data\":[{\"author\":{\"username\":\"carol\"},\"text\":\"from data\",\"id\":\"1\"}]}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/twitter/tweet/advanced_search",
                exchange -> respond(exchange, payload.get()));
        server.start();
        try {
            var gateway = new SocialSearchProvidersAccess().getxapi(json, server, "synthetic");
            var hits = gateway.search("topic", List.of(), 5);
            assertEquals(1, hits.size());
            assertEquals("@carol: from data", hits.get(0).title());

            payload.set("{}");
            assertEquals(0, gateway.search("topic", List.of(), 5).size());

            payload.set("{\"tweets\":null,\"data\":null}");
            assertEquals(0, gateway.search("topic", List.of(), 5).size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getxapiReportsHttpErrorsAndMissingKey() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/twitter/tweet/advanced_search", exchange -> {
            byte[] body = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var gateway = new SocialSearchProvidersAccess().getxapi(json, server, "synthetic");
            var error = assertThrows(IllegalStateException.class,
                    () -> gateway.search("topic", List.of(), 5));
            assertTrue(error.getMessage().contains("429"));

            var noKey = new SocialSearchProvidersAccess().getxapi(json, server, null);
            var missing = assertThrows(IllegalStateException.class,
                    () -> noKey.search("topic", List.of(), 5));
            assertTrue(missing.getMessage().contains("GETXAPI_API_KEY"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void xquikSendsApiKeyHeaderAndCapsLimitAtTwoHundred() throws Exception {
        var apiKey = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/x/tweets/search", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            query.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"tweets":[
                      {"author":{"username":"dave"},"text":"xquik text","id":"42",
                       "likeCount":9,"retweetCount":4,"viewCount":0},
                      {"author":null,"text":null,"id":null},
                      {"author":{"username":"erin"},"text":"%s","id":"43","likeCount":1,"retweetCount":0,"viewCount":12}
                    ]}
                    """.formatted("长".repeat(200)));
        });
        server.start();
        try {
            var gateway = new SocialSearchProvidersAccess().xquik(json, server, "secret");
            var hits = gateway.search("AI 观察", List.of(), 500);
            assertEquals(3, hits.size());
            assertEquals("secret", apiKey.get());
            assertTrue(query.get().contains("q=AI 观察"));
            assertTrue(query.get().contains("limit=200"));
            assertTrue(query.get().contains("queryType=Top"));

            assertEquals("@dave: xquik text", hits.get(0).title());
            assertEquals("https://x.com/dave/status/42", hits.get(0).url());
            assertEquals("xquik text\n\n[9 likes, 4 RTs]", hits.get(0).content());

            assertEquals("@unknown: ", hits.get(1).title());
            assertEquals("https://x.com/unknown/status/", hits.get(1).url());
            assertEquals("\n\n[0 likes, 0 RTs]", hits.get(1).content());

            assertTrue(hits.get(2).title().endsWith("..."));
            assertTrue(hits.get(2).content().contains("[1 likes, 0 RTs, 12 views]"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void xquikToleratesMissingTweetArraysAndRejectsBadArguments() throws Exception {
        var payload = new AtomicReference<>("{\"tweets\":null}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/x/tweets/search", exchange -> respond(exchange, payload.get()));
        server.start();
        try {
            var gateway = new SocialSearchProvidersAccess().xquik(json, server, "secret");
            assertEquals(0, gateway.search("topic", List.of(), 5).size());
            payload.set("{\"results\":[]}");
            assertEquals(0, gateway.search("topic", List.of(), 5).size());

            assertThrows(IllegalArgumentException.class, () -> gateway.search(" ", List.of(), 5));
            assertThrows(IllegalArgumentException.class, () -> gateway.search("topic", List.of(), 0));
            var error = assertThrows(IllegalStateException.class,
                    () -> new SocialSearchProvidersAccess().xquik(json, server, " ").search("topic", List.of(), 5));
            assertTrue(error.getMessage().contains("XQUIK_API_KEY"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void factorySelectsSocialAdaptersAndKeepsTheRestAt501() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/getxapi", exchange -> respond(exchange,
                "{\"tweets\":[{\"author\":{\"userName\":\"a\"},\"text\":\"t\",\"id\":\"1\"}]}"));
        server.createContext("/xquik", exchange -> respond(exchange,
                "{\"tweets\":[{\"author\":{\"username\":\"b\"},\"text\":\"u\",\"id\":\"2\"}]}"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var env = new MockEnvironment()
                    .withProperty("GETXAPI_API_KEY", "synthetic-getxapi")
                    .withProperty("XQUIK_API_KEY", "synthetic-xquik")
                    .withProperty("GETXAPI_BASE_URL", base + "/getxapi")
                    .withProperty("XQUIK_BASE_URL", base + "/xquik");
            assertEquals(1, SocialSearchProvidersAccess.fromFactory("getxapi", env, json)
                    .search("topic", List.of(), 5).size());
            assertEquals(1, SocialSearchProvidersAccess.fromFactory("xquik", env, json)
                    .search("topic", List.of(), 5).size());
            assertTrue(ProviderCatalog.usable(ProviderCatalog.Kind.SEARCH, "getxapi"));
            assertTrue(ProviderCatalog.usable(ProviderCatalog.Kind.SEARCH, "xquik"));
            assertThrows(UnsupportedProviderException.class,
                    () -> SearchProviderFactory.create("unknown-social", env, json, Map.of()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void domainFilterStillAppliesToSocialHits() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/x/tweets/search", exchange -> respond(exchange,
                "{\"tweets\":[{\"author\":{\"username\":\"b\"},\"text\":\"u\",\"id\":\"2\"}]}"));
        server.start();
        try {
            var gateway = new SocialSearchProvidersAccess().xquik(json, server, "secret");
            assertEquals(0, gateway.search("topic", List.of("example.org"), 5).size());
            assertEquals(1, gateway.search("topic", List.of("x.com"), 5).size());
        } finally {
            server.stop(0);
        }
    }

    /** Small builder so tests never depend on the exact factory timeout wiring. */
    private static final class SocialSearchProvidersAccess {
        SearchGateway getxapi(ObjectMapper json, HttpServer server, String key) {
            return SocialSearchProviders.getxapi(java.net.http.HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + "/twitter/tweet/advanced_search"), key, json,
                    Duration.ofSeconds(5));
        }

        SearchGateway xquik(ObjectMapper json, HttpServer server, String key) {
            return SocialSearchProviders.xquik(java.net.http.HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + "/api/v1/x/tweets/search"), key, json, Duration.ofSeconds(5));
        }

        static SearchGateway fromFactory(String provider, MockEnvironment env, ObjectMapper json) {
            return SearchProviderFactory.create(provider, env, json, Map.of());
        }
    }

    private static void respond(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
