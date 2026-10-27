package io.researchrunner.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.researchrunner.report.HistoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HistoryController {
    private final HistoryService history;

    public HistoryController(HistoryService history) {
        this.history = history;
    }

    @GetMapping("/api/reports")
    public Map<String, Object> reports(
            @RequestParam(name = "report_ids", required = false) String ids,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime cursor,
            @RequestParam(name = "include_incomplete", defaultValue = "false") boolean incomplete) {
        var reports = history.reports(ids == null || ids.isEmpty() ? null : Arrays.asList(ids.split(",", -1)),
                limit, cursor, incomplete);
        String next = reports.size() == limit
                ? Instant.ofEpochMilli(reports.getLast().path("timestamp").asLong())
                    .atOffset(ZoneOffset.UTC).toString() : null;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reports", reports);
        response.put("next_cursor", next);
        return response;
    }

    @GetMapping("/api/reports/{id}")
    public Map<String, JsonNode> report(@PathVariable String id) {
        return Map.of("report", history.report(id));
    }

    @GetMapping("/api/reports/{id}/chat")
    public Map<String, JsonNode> chat(@PathVariable String id) {
        return Map.of("chatMessages", history.report(id).path("chatMessages"));
    }

    @GetMapping("/api/documents")
    public Map<String, Object> documents() {
        return Map.of("documents", history.documents());
    }

    @GetMapping("/files/")
    public Map<String,Object> files() {
        var documents=history.documents();
        return Map.of("files",documents.stream().map(document -> document.path("filename").asText()).toList(),"documents",documents);
    }
}
