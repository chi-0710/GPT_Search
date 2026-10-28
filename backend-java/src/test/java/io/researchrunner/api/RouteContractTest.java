package io.researchrunner.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.researchrunner.infrastructure.HistoryMapper;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** A1-05: with writes enabled the Java service must register every Python HTTP route. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"researchrunner.writes-enabled=true", "spring.datasource.hikari.read-only=false"})
class RouteContractTest {
    private static final Set<String> EXPECTED = Set.of(
            "/", "/.well-known/agent-discovery.json", "/report/{id}", "/health/ready",
            "/api/artifacts/{id}", "/outputs/{*path}",
            "/api/reports", "/api/reports/{id}", "/api/reports/{id}/chat",
            "/api/research-runs/{id}", "/api/research-runs/{id}/events", "/api/research-runs/{id}/log",
            "/report/", "/files/", "/api/documents", "/api/documents/{id}", "/api/documents/{id}/content",
            "/api/documents/{id}/reindex",
            "/api/reports/{id}/evidence", "/api/research-runs/{id}/evidence",
            "/api/research-runs/{id}/evidence/{number}", "/api/research-runs/{id}/evidence/{number}/view",
            "/api/multi_agents", "/upload/", "/files/{filename}", "/api/chat");

    @Autowired
    private RequestMappingHandlerMapping mappings;

    @MockitoBean
    private HistoryMapper mapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @Test
    void allPythonHttpRoutesAreRegistered() {
        Set<String> actual = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .collect(Collectors.toSet());
        for (String path : EXPECTED) {
            assertTrue(actual.contains(path), "missing route: " + path + " (present=" + actual + ")");
        }
    }
}
