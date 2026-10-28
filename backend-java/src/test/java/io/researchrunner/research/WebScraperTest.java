package io.researchrunner.research;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebScraperTest {
    @Test void extractsChineseHtmlAndRejectsRedirectOutsideDomain() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/article",exchange->{
            byte[] content="<html><head><title>研究证据</title></head><body><nav>导航</nav><article>可信正文</article><script>secret()</script></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
            exchange.sendResponseHeaders(200,content.length);exchange.getResponseBody().write(content);exchange.close();
        });
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().set("Location","https://example.org/private");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.start();
        try {
            var scraper=new WebScraper(Duration.ofSeconds(2),4096,true);
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var result=scraper.fetch(base+"/article",List.of("127.0.0.1"));
            assertEquals("研究证据",result.title());assertEquals("可信正文",result.content());
            assertThrows(IllegalArgumentException.class,()->scraper.fetch(base+"/redirect",List.of("127.0.0.1")));
            assertThrows(IllegalStateException.class,()->new WebScraper(Duration.ofSeconds(2),10,true).fetch(base+"/article",List.of()));
            assertThrows(IllegalArgumentException.class,()->new WebScraper(Duration.ofSeconds(2),4096).fetch(base+"/article",List.of("127.0.0.1")));
        } finally {server.stop(0);}
    }
}
