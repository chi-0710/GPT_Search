package io.researchrunner.api;

import io.researchrunner.infrastructure.HistoryMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadinessController {
    private final HistoryMapper mapper;
    private final String expected;
    private final boolean requireFlyway;
    private final String expectedJava;

    public ReadinessController(HistoryMapper mapper,
            @Value("${researchrunner.expected-migration}") String expected,
            @Value("${JAVA_REQUIRE_FLYWAY:false}") boolean requireFlyway,
            @Value("${JAVA_EXPECTED_MIGRATION:1}") String expectedJava) {
        this.mapper = mapper;
        this.expected = expected;
        this.requireFlyway=requireFlyway;this.expectedJava=expectedJava;
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("ready", false);
        status.put("database", "unavailable");
        status.put("pgvector", false);
        status.put("migration", null);
        status.put("expected_migration", expected);
        try {
            boolean vector = mapper.vectorEnabled();
            String migration = mapper.migrationTableExists() ? mapper.migrationVersion() : null;
            status.put("database", "ok");
            status.put("pgvector", vector);
            status.put("migration", migration);
            boolean managed=mapper.flywayTableExists();String javaVersion=managed?mapper.flywayVersion():null;
            status.put("java_migration",javaVersion);
            status.put("ready", vector && expected.equals(migration)
                    && (managed?expectedJava.equals(javaVersion) && mapper.flywayHealthy():!requireFlyway));
        } catch (DataAccessException exception) {
            // Never expose a JDBC URL, SQL statement, credentials or driver exception.
            status.put("error", "PostgreSQL 存储服务不可用");
        }
        return ResponseEntity.status(Boolean.TRUE.equals(status.get("ready")) ? 200 : 503).body(status);
    }
}
