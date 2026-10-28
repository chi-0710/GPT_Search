package io.researchrunner.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.infrastructure.HistoryMapper;
import io.researchrunner.report.HistoryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HistoryControllerTest {
    private HistoryMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mapper = mock(HistoryMapper.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new HistoryController(new HistoryService(mapper, new ObjectMapper())),
                new ReadinessController(mapper, "0002_shared_library",false,"1"))
                .setControllerAdvice(new ApiErrors()).build();
    }

    @Test
    void historyPreservesPublicIdChatAndPagination() throws Exception {
        when(mapper.reports(isNull(), eq(1), isNull(), eq(false))).thenReturn(List.of("""
            {"id":"legacy-id","timestamp":1000,"orderedData":[],"chatMessages":[
              {"id":"message","role":"user","content":"来源？","timestamp":500}]}
            """));
        mvc.perform(get("/api/reports").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reports[0].id").value("legacy-id"))
                .andExpect(jsonPath("$.reports[0].chatMessages[0].content").value("来源？"))
                .andExpect(jsonPath("$.next_cursor").value("1970-01-01T00:00:01Z"));
    }

    @Test
    void missingReportAndChatReturnOriginalErrorShape() throws Exception {
        for (String path : List.of("/api/reports/missing", "/api/reports/missing/chat")) {
            mvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("未找到报告"));
        }
    }

    @Test
    void invalidLimitsAndCursorNeverReachDatabase() throws Exception {
        for (String limit : List.of("0", "201", "invalid")) {
            mvc.perform(get("/api/reports").param("limit", limit)).andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(get("/api/reports").param("cursor", "invalid")).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(mapper);
    }

    @Test
    void explicitIdsAndCursorArePassedWithoutChangingHistoricalModes() throws Exception {
        when(mapper.reports(anyList(), eq(50), any(), eq(false))).thenReturn(List.of());
        mvc.perform(get("/api/reports").param("report_ids", "a,b")
                .param("cursor", "2026-01-01T00:00:00Z")).andExpect(status().isOk());
        verify(mapper).reports(eq(List.of("a", "b")), eq(50),
                eq(java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z")), eq(false));
    }

    @Test
    void databaseFailuresDoNotLeakCredentials() throws Exception {
        when(mapper.documents()).thenThrow(new DataAccessResourceFailureException("password=secret"));
        mvc.perform(get("/api/documents")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("PostgreSQL 存储服务不可用"));
        when(mapper.vectorEnabled()).thenThrow(new DataAccessResourceFailureException("password=secret"));
        mvc.perform(get("/health/ready")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("PostgreSQL 存储服务不可用"))
                .andExpect(jsonPath("$.ready").value(false));
    }

    @Test
    void readinessRequiresBothVectorAndExpectedMigration() throws Exception {
        when(mapper.vectorEnabled()).thenReturn(true);
        when(mapper.migrationTableExists()).thenReturn(true);
        when(mapper.migrationVersion()).thenReturn("0001_postgres_persistence");
        mvc.perform(get("/health/ready")).andExpect(status().isServiceUnavailable());
        when(mapper.migrationVersion()).thenReturn("0002_shared_library");
        mvc.perform(get("/health/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        when(mapper.vectorEnabled()).thenReturn(false);
        mvc.perform(get("/health/ready")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void requiredFlywayRejectsMissingFailedAndUnexpectedVersions() {
        when(mapper.vectorEnabled()).thenReturn(true);
        when(mapper.migrationTableExists()).thenReturn(true);
        when(mapper.migrationVersion()).thenReturn("0002_shared_library");
        var controller=new ReadinessController(mapper,"0002_shared_library",true,"1");
        org.junit.jupiter.api.Assertions.assertEquals(503,controller.ready().getStatusCode().value());
        when(mapper.flywayTableExists()).thenReturn(true);
        when(mapper.flywayVersion()).thenReturn("1");
        org.junit.jupiter.api.Assertions.assertEquals(503,controller.ready().getStatusCode().value());
        when(mapper.flywayHealthy()).thenReturn(true);
        org.junit.jupiter.api.Assertions.assertEquals(200,controller.ready().getStatusCode().value());
        when(mapper.flywayVersion()).thenReturn("2");
        org.junit.jupiter.api.Assertions.assertEquals(503,controller.ready().getStatusCode().value());
    }

    @Test
    void missingMigrationTableIsNotReadyAndIsNotQueried() throws Exception {
        when(mapper.vectorEnabled()).thenReturn(true);
        mvc.perform(get("/health/ready")).andExpect(status().isServiceUnavailable());
        verify(mapper, never()).migrationVersion();
    }

    @Test
    void writeEndpointsAreNotExposedDuringReadOnlyMigration() throws Exception {
        mvc.perform(post("/api/reports").contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(mapper);
    }
}
