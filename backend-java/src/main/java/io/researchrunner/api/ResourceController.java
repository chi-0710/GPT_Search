package io.researchrunner.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.researchrunner.infrastructure.StoredFile;
import io.researchrunner.report.ResourceService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

@RestController
public class ResourceController {
    private final ResourceService resources;

    public ResourceController(ResourceService resources) { this.resources = resources; }

    @GetMapping("/api/research-runs/{id}")
    public Map<String, JsonNode> run(@PathVariable String id) { return Map.of("run", resources.run(id)); }

    @GetMapping("/api/research-runs/{id}/events")
    public Map<String, Object> events(@PathVariable String id,
            @RequestParam(name="after_id", defaultValue="0") @Min(0) long after,
            @RequestParam(defaultValue="500") @Min(1) @Max(2000) int limit) {
        return Map.of("events", resources.events(id, after, limit));
    }

    @GetMapping({"/api/reports/{id}/evidence", "/api/research-runs/{id}/evidence"})
    public Map<String, Object> evidence(@PathVariable String id) {
        return Map.of("evidence", resources.evidence(id));
    }

    @GetMapping("/api/research-runs/{id}/evidence/{number}")
    public JsonNode evidenceItem(@PathVariable String id, @PathVariable int number) {
        return resources.evidenceItem(id, number);
    }

    @GetMapping("/api/research-runs/{id}/evidence/{number}/view")
    public ResponseEntity<?> evidenceView(@PathVariable String id, @PathVariable int number) {
        JsonNode item = resources.evidenceItem(id, number);
        String type = item.path("source_type").asText();
        if (type.equals("web")) {
            String url = item.path("url").asText("").strip();
            try {
                URI uri = URI.create(url);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getHost().isBlank()
                        || url.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                    throw new IllegalArgumentException();
                }
                return ResponseEntity.status(302).location(uri).build();
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "该引用没有有效的原始网页地址");
            }
        }
        String original = "<p>原文件已删除，以下引用片段仍可查看。</p>";
        if (type.equals("mcp")) {
            original = "<p>工具检索结果：以下为本次研究使用的内容快照。</p>";
        } else if (item.path("document_id").asText("").isBlank()
                && item.path("url").asText("").matches("^https?://\\S+$")) {
            String remote = escape(item.path("url").asText());
            original = "<p><a href=\"" + remote + "\" rel=\"noopener noreferrer\">打开原始文档</a></p>"
                    + "<p>该文档来自远程 URL，未保存原文件。</p>";
        } else if (item.path("original_available").asBoolean()) {
            String path = "/api/documents/" + encoded(item.path("document_id").asText()) + "/content";
            original = "<p><a href=\"" + path + "\">下载原文件</a></p>";
            if (item.path("title").asText().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                JsonNode page = item.path("page");
                String fragment = page.isIntegralNumber() && page.asLong() > 0 ? "#page=" + page.asLong() : "";
                String pdf = path + "?download=false" + fragment;
                original += "<p><a href=\"" + pdf + "\">打开 PDF 对应位置</a></p>"
                        + "<iframe title=\"PDF 原文\" src=\"" + pdf + "\" style=\"width:100%;height:55vh\"></iframe>";
            }
        }
        String location = item.hasNonNull("page") ? "第 " + item.path("page").asText() + " 页"
                : "片段 " + item.path("chunk_id").asText(String.valueOf(number));
        String html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width\"><title>引用 " + number + "</title>"
                + "<body style=\"max-width:1000px;margin:2em auto;padding:1em;font-family:sans-serif\"><h1>"
                + escape(item.path("title").asText()) + "</h1><p>" + escape(location) + "</p>" + original
                + "<h2>研究时使用的证据片段</h2><pre style=\"white-space:pre-wrap\">"
                + escape(item.path("content").asText()) + "</pre></body></html>";
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-src 'self'; base-uri 'none'")
                .body(html);
    }

    @GetMapping("/api/artifacts/{id}")
    public ResponseEntity<byte[]> artifact(@PathVariable String id,
            @RequestParam(defaultValue="false") boolean download) {
        return artifactResponse(resources.artifact(id), download);
    }

    @GetMapping("/report/{id}")
    public ResponseEntity<byte[]> reportDocument(@PathVariable String id) {
        return artifactResponse(resources.reportDocument(id), true);
    }

    @GetMapping("/outputs/{*path}")
    public ResponseEntity<byte[]> legacyArtifact(@PathVariable String path) {
        return artifactResponse(resources.legacyArtifact(path), false);
    }

    @GetMapping("/api/documents/{id}/content")
    public ResponseEntity<byte[]> document(@PathVariable String id,
            @RequestParam(defaultValue="true") boolean download) {
        StoredFile file = resources.document(id);
        boolean pdf = file.filename().toLowerCase(Locale.ROOT).endsWith(".pdf")
                && file.content().length >= 5
                && new String(file.content(), 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
        return ResponseEntity.ok().contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", disposition(file.filename(), !pdf || download))
                .header("X-Content-Type-Options", "nosniff").body(file.content());
    }

    @GetMapping("/api/research-runs/{id}/log")
    public ResponseEntity<Map<String, Object>> log(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", disposition(id + ".json", true)).body(resources.log(id));
    }

    private ResponseEntity<byte[]> artifactResponse(StoredFile file, boolean download) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType()))
                .contentLength(file.content().length).eTag(file.sha256())
                .header("Content-Disposition", disposition(file.filename(), download)).body(file.content());
    }

    private String disposition(String filename, boolean download) {
        return (download ? "attachment" : "inline") + "; filename*=UTF-8''" + encoded(filename);
    }
    private String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private String escape(String value) { return HtmlUtils.htmlEscape(value, "UTF-8"); }
}
