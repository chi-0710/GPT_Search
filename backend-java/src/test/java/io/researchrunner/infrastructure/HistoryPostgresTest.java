package io.researchrunner.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: only the dedicated Compose test database, never the application database. */
@EnabledIfEnvironmentVariable(named = "JAVA_TEST_DATABASE_URL", matches = ".+")
class HistoryPostgresTest {
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void setup() throws Exception {
        String url = System.getenv("JAVA_TEST_DATABASE_URL");
        // Guard against accidentally pointing fixture DDL at the user's database.
        if (!url.endsWith("/researchrunner_java_test")) {
            throw new IllegalArgumentException("Use the dedicated researchrunner_java_test database");
        }
        var dataSource = new DriverManagerDataSource(url, "java_test", "java_test");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            String fixture = new String(HistoryPostgresTest.class.getResourceAsStream("/history-fixture.sql")
                    .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            statement.execute(fixture);
        }
        var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(HistoryMapper.class);
        configuration.addMapper(ResourceMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void resourcesPreserveRunIdentityEvidenceAndBinary() throws Exception {
        try (var session = sessions.openSession()) {
            var mapper = session.getMapper(ResourceMapper.class);
            var json = new ObjectMapper();
            var run = json.readTree(mapper.runByReport("old-report"));
            var id = java.util.UUID.fromString(run.path("id").asText());
            assertEquals("deep", run.path("report_type").asText());
            assertEquals(run, json.readTree(mapper.runById(id)));
            var evidence = mapper.evidence(id).stream().map(value -> {
                try { return json.readTree(value); } catch (Exception e) { throw new RuntimeException(e); }
            }).toList();
            assertTrue(evidence.get(0).path("original_available").asBoolean());
            assertFalse(evidence.get(1).path("original_available").asBoolean());
            assertTrue(evidence.get(2).path("original_available").asBoolean());
            assertEquals(1, mapper.events(id, 0, 1).size());
            var event = json.readTree(mapper.events(id, 0, 1).getFirst());
            assertEquals(1, event.path("id").asLong());
            assertTrue(mapper.events(id, 1, 50).isEmpty());
            assertEquals("https://example.com", json.readTree(mapper.sources(id).getFirst()).path("url").asText());
            var file = mapper.reportDocument("old-report");
            assertArrayEquals(new byte[] {0, 1, 2, 3}, file.content());
            assertEquals("报告.docx", file.filename());
            assertArrayEquals(file.content(), mapper.artifact(java.util.UUID.fromString("00000000-0000-0000-0000-000000000301")).content());
            assertArrayEquals(file.content(), mapper.legacyArtifact("report.docx", "outputs/report.docx").content());
            assertEquals("资料.pdf", mapper.document(java.util.UUID.fromString("00000000-0000-0000-0000-000000000021")).filename());
        }
    }

    @Test
    void preservesJsonTypesTimestampAndChatOrder() throws Exception {
        try (var session = sessions.openSession()) {
            var mapper = session.getMapper(HistoryMapper.class);
            var reports = mapper.reports(null, 50, null, false);
            assertEquals(1, reports.size());
            var report = new ObjectMapper().readTree(reports.getFirst());
            assertEquals("old-report", report.path("id").asText());
            assertTrue(report.path("orderedData").isArray());
            assertEquals("先提问", report.path("chatMessages").get(0).path("content").asText());
            assertEquals(100, report.path("chatMessages").get(0).path("timestamp").asLong());
            assertFalse(report.path("chatMessages").get(0).has("metadata"));
            assertTrue(report.path("chatMessages").get(1).path("metadata").isObject());
            assertFalse(report.path("chatMessages").get(2).has("metadata"));
            assertEquals(1767225600123L, report.path("timestamp").asLong());
        }
    }

    @Test
    void explicitIdsIncludeUnfinishedReportsButCursorStillApplies() {
        try (var session = sessions.openSession()) {
            var mapper = session.getMapper(HistoryMapper.class);
            assertEquals(1, mapper.reports(List.of("draft"), 50, null, false).size());
            assertEquals(2, mapper.reports(null, 50, null, true).size());
            assertTrue(mapper.reports(null, 50, OffsetDateTime.parse("2026-01-01T00:00:00Z"), true).isEmpty());
            assertNull(mapper.report("unknown"));
            assertTrue(mapper.reports(List.of("' OR 1=1 --"), 50, null, true).isEmpty());
        }
    }

    @Test
    void documentProjectionOmitsBinaryAndReadinessReadsExistingSchema() throws Exception {
        try (var session = sessions.openSession()) {
            var mapper = session.getMapper(HistoryMapper.class);
            var document = new ObjectMapper().readTree(mapper.documents().getFirst());
            assertEquals("资料.pdf", document.path("filename").asText());
            assertFalse(document.has("content"));
            assertTrue(document.has("embedding_profile_id"));
            assertTrue(mapper.vectorEnabled());
            assertTrue(mapper.migrationTableExists());
            assertEquals("0002_shared_library", mapper.migrationVersion());
        }
    }
}
