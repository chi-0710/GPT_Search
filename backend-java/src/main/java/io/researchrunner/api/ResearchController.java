package io.researchrunner.api;

import io.researchrunner.research.ResearchExecutionService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HTTP transport delegates to the shared Java research execution service. */
@RestController
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class ResearchController {
    private final ResearchExecutionService research;
    public ResearchController(ResearchExecutionService research) {this.research=research;}

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail","研究参数或供应商配置无效"));
    }
    @PostMapping("/api/multi_agents") public Map<String,Object> defaultResearch() throws Exception {
        return report(Map.of("task","Is AI in a hype cycle?","report_type","multi_agents","report_source","web","tone","Objective","generate_in_background",false));
    }
    @PostMapping("/report/") public Map<String,Object> report(@RequestBody Map<String,Object> request) throws Exception {
        return research.report(request);
    }
}
