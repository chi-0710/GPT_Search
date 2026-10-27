package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TavilySearchTest {
    @Test void invalidDomainsFailClosedAndSuffixSpoofingIsRejected() {
        assertTrue(new DomainPolicy(List.of("https://user:pass@example.com")).deniesAll());
        var policy=new DomainPolicy(List.of("EXAMPLE.com/path"));
        assertTrue(policy.permits("https://a.example.com/one"));
        assertFalse(policy.permits("https://example.com.evil.test"));
        assertFalse(policy.permits("file:///tmp/data"));
        assertEquals("xn--fiqs8s.cn",DomainPolicy.hostname("中国.cn",true));
        assertNull(DomainPolicy.hostname("https://example.com:99999",false));
    }

    @Test void protocolPreservesRestrictionsAndFiltersMalformedAndDuplicateHits() throws Exception {
        var json=new ObjectMapper();
        var captured=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search",exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] response="""
                {"results":[null,{"url":"https://evil.test","content":"bad"},
                {"url":"https://docs.example.com/a","snippet":"证据"},
                {"url":"https://docs.example.com/a","content":"duplicate"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var gateway=new TavilySearch(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/search"),"test-secret",json,Duration.ofSeconds(2));
            var hits=gateway.search("研究 site:evil.test",List.of("example.com"),2);
            assertEquals(1,hits.size());assertEquals("证据",hits.getFirst().content());
            var sent=json.readTree(captured.get());
            assertEquals("研究",sent.path("query").asText());assertEquals("example.com",sent.path("include_domains").get(0).asText());
            captured.set(null);assertTrue(gateway.search("研究",List.of("bad domain"),2).isEmpty());assertNull(captured.get());
        } finally {server.stop(0);}
    }

    @Test void providerErrorsDoNotLeakResponseOrCredentials() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search",exchange -> {
            byte[] bytes="sensitive-provider-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var gateway=new TavilySearch(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/search"),"test-secret",new ObjectMapper(),Duration.ofSeconds(2));
            var error=assertThrows(IllegalStateException.class,()->gateway.search("query",List.of(),1));
            assertTrue(error.getMessage().contains("401"));assertFalse(error.getMessage().contains("sensitive"));assertFalse(error.getMessage().contains("test-secret"));
        } finally {server.stop(0);}
    }
}
