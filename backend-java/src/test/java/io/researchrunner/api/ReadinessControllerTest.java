package io.researchrunner.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.researchrunner.infrastructure.HistoryMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A1-04: readiness must require Flyway history once the Java deployment owns migrations. */
class ReadinessControllerTest {
    private final HistoryMapper mapper = Mockito.mock(HistoryMapper.class);
    private final ReadinessController controller =
            new ReadinessController(mapper, "0002_shared_library", true, "1");

    @Test
    void readyOnlyWhenAlembicStateAndFlywayHistoryAreBothHealthy() {
        when(mapper.vectorEnabled()).thenReturn(true);
        when(mapper.migrationTableExists()).thenReturn(true);
        when(mapper.migrationVersion()).thenReturn("0002_shared_library");
        when(mapper.flywayTableExists()).thenReturn(true);
        when(mapper.flywayVersion()).thenReturn("1");
        when(mapper.flywayHealthy()).thenReturn(true);

        Map<String, Object> status = controller.ready().getBody();

        assertEquals(200, controller.ready().getStatusCode().value());
        assertEquals(true, status.get("ready"));
        assertEquals("ok", status.get("database"));
        assertEquals(true, status.get("pgvector"));
        assertEquals("0002_shared_library", status.get("migration"));
        assertEquals("1", status.get("java_migration"));
    }

    @Test
    void notReadyWhenFlywayIsRequiredButMissing() {
        when(mapper.vectorEnabled()).thenReturn(true);
        when(mapper.migrationTableExists()).thenReturn(true);
        when(mapper.migrationVersion()).thenReturn("0002_shared_library");
        when(mapper.flywayTableExists()).thenReturn(false);

        Map<String, Object> status = controller.ready().getBody();

        assertEquals(503, controller.ready().getStatusCode().value());
        assertEquals(false, status.get("ready"));
        assertFalse((Boolean) status.get("ready"));
    }

    @Test
    void notReadyWhenSchemaIsBehind() {
        when(mapper.vectorEnabled()).thenReturn(true);
        when(mapper.migrationTableExists()).thenReturn(true);
        when(mapper.migrationVersion()).thenReturn("0001_postgres_persistence");

        Map<String, Object> status = controller.ready().getBody();

        assertEquals(503, controller.ready().getStatusCode().value());
        assertTrue(status.get("expected_migration").equals("0002_shared_library"));
    }
}
