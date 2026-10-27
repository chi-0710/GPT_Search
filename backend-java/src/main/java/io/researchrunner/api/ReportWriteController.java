package io.researchrunner.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.researchrunner.report.ReportWriteService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="researchrunner.writes-enabled", havingValue="true")
public class ReportWriteController {
    private final ReportWriteService reports;
    public ReportWriteController(ReportWriteService reports) { this.reports = reports; }

    @PostMapping("/api/reports")
    public Map<String, Object> save(@RequestBody JsonNode input) {
        return Map.of("success", true, "id", reports.upsert(input));
    }

    @PutMapping("/api/reports/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody JsonNode input) {
        reports.patch(id, input);
        return Map.of("success", true, "id", id);
    }

    @DeleteMapping("/api/reports/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        reports.delete(id);
        return Map.of("success", true);
    }

    @DeleteMapping("/api/reports")
    public Map<String, Object> clear() {
        return Map.of("success", true, "deleted", reports.clear());
    }

    @PostMapping("/api/reports/{id}/chat")
    public Map<String, Object> chat(@PathVariable String id, @RequestBody JsonNode input) {
        reports.addMessage(id, input);
        return Map.of("success", true, "id", id);
    }
}
