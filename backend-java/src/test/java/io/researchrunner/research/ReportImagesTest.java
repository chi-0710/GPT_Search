package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** A3-13 pipeline: section images become artifact-backed Markdown and ReportFigures. */
class ReportImagesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void embedsGeneratedImageAfterSectionHeading() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        String encoded = Base64.getEncoder().encodeToString(png);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/gemini-test:generateContent", exchange -> {
            String response = """
                    {"candidates":[{"content":{"parts":[
                      {"inlineData":{"mimeType":"image/png","data":"%s"}}]}}]}
                    """.formatted(encoded);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var env = new MockEnvironment()
                    .withProperty("IMAGE_GENERATION_ENABLED", "true")
                    .withProperty("IMAGE_GENERATION_PROVIDER", "google")
                    .withProperty("IMAGE_GENERATION_MODEL", "gemini-test")
                    .withProperty("IMAGE_GENERATION_MAX_IMAGES", "1")
                    .withProperty("GOOGLE_API_KEY", "synthetic")
                    .withProperty("GOOGLE_IMAGE_BASE_URL",
                            "http://127.0.0.1:" + server.getAddress().getPort());
            String markdown = "# 报告\n\n## 市场概况\n\n正文\n\n## 结论\n\n结束";
            var result = ReportImages.enrich(markdown, "研究主题", env, json, "http://localhost:8000");
            assertEquals(1, result.figures().size());
            assertEquals(1, result.images().size());
            assertTrue(result.markdown().contains("![市场概况](http://localhost:8000/api/artifacts/"));
            assertTrue(result.markdown().contains("## 市场概况"));
            assertFalse(result.markdown().contains("![结论]"));
            assertEquals("市场概况", result.images().getFirst().get("alt_text"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledReturnsInputUnchanged() {
        var env = new MockEnvironment().withProperty("IMAGE_GENERATION_ENABLED", "false");
        String markdown = "## 章节\n正文";
        var result = ReportImages.enrich(markdown, "主题", env, json, "http://localhost:8000");
        assertEquals(markdown, result.markdown());
        assertTrue(result.figures().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }
}
