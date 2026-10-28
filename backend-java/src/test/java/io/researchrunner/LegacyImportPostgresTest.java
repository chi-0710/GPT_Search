package io.researchrunner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.document.DocumentStore;
import io.researchrunner.document.EmbeddingGateway;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A4-11 against a real, isolated pgvector database: two legacy reports (referenced outputs, chat,
 * events), orphaned output format variants sharing one archive run, and a document.
 *
 * <p>Like {@link DocumentReindexPostgresTest}, this class creates its own database from
 * {@code JAVA_WRITE_TEST_DATABASE_URL} (which must end with
 * {@code /researchrunner_java_write_test}), migrates it explicitly and drops it afterwards. The
 * Compose application database/volume is never touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "JAVA_WRITE_TEST_DATABASE_URL", matches = ".+")
class LegacyImportPostgresTest {
    private static final String DATABASE = "researchrunner_legacy_" + UUID.randomUUID().toString().replace("-", "");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static DriverManagerDataSource admin;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("JAVA_WRITE_TEST_DATABASE_URL");
        if (url == null || !url.endsWith("/researchrunner_java_write_test")) {
            throw new IllegalStateException("Only isolated test database allowed");
        }
        admin = new DriverManagerDataSource(url, "java_test", "java_test");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + DATABASE);
        String target = url.replace("/researchrunner_java_write_test", "/" + DATABASE);
        JavaMigrations.migrate(new DriverManagerDataSource(target, "java_test", "java_test"), false);
        registry.add("spring.datasource.url", () -> target);
        registry.add("spring.datasource.username", () -> "java_test");
        registry.add("spring.datasource.password", () -> "java_test");
        registry.add("spring.datasource.hikari.read-only", () -> false);
        registry.add("researchrunner.writes-enabled", () -> true);
    }

    @AfterAll
    static void cleanup(@Autowired DataSource source) {
        if (source instanceof com.zaxxer.hikari.HikariDataSource pool) pool.close();
        if (admin != null) new JdbcTemplate(admin).execute("DROP DATABASE " + DATABASE);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired LegacyImportService service;
    @Autowired DocumentStore documents;
    @Autowired ObjectMapper json;
    @MockitoBean EmbeddingGateway embeddings;
    @TempDir Path root;

    @BeforeEach
    void reset() {
        for (String table : List.of("research_events", "artifacts", "research_runs", "chat_messages", "reports",
                "migration_imports", "document_chunks", "documents", "embedding_profiles")) {
            jdbc.update("DELETE FROM " + table);
        }
        when(embeddings.configuration()).thenReturn(new EmbeddingGateway.Profile("test", "test-model", 3));
        when(embeddings.embed(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).stream()
                .map(text -> new float[]{1, 0, 0}).toList());
    }

    @Test
    void importsTwoReportsOutputsAndDocumentThenRepeatsWithoutAnyNewRows() throws Exception {
        fixture();
        Map<String,Object> first = service.importAll(root);

        assertEquals(2, first.get("reports"));
        assertEquals(3, first.get("referenced_outputs"));
        assertEquals(5, first.get("output_files"));
        assertEquals(1, first.get("documents"));
        assertEquals(1, first.get("imported_documents"));
        assertEquals(List.of("outputs/orphan/legacy.md", "outputs/orphan/legacy.pdf"), first.get("orphan_outputs"));
        assertEquals(false, first.get("dry_run"));
        assertNull(first.get("failed_documents"));

        // Reports keep public id, ordered data, chat history and legacy timestamps.
        assertEquals(2, count("reports"));
        Map<String,Object> r1 = jdbc.queryForMap("""
                SELECT public_id, question, answer, status, ordered_data::text AS ordered,
                       (round(extract(epoch FROM created_at) * 1000))::bigint AS created_ms,
                       completed_at IS NOT NULL AS completed
                FROM reports WHERE public_id='r1'
                """);
        assertEquals("第一份遗留报告", r1.get("question"));
        assertEquals("# 正文一", r1.get("answer"));
        assertEquals("completed", r1.get("status"));
        assertEquals(2, JSON.readTree((String) r1.get("ordered")).size());
        assertEquals(1700000000000L, ((Number) r1.get("created_ms")).longValue());
        assertEquals(true, r1.get("completed"));
        Map<String,Object> chat = jdbc.queryForMap("""
                SELECT c.role, c.content, c.client_timestamp FROM chat_messages c
                JOIN reports r ON r.id=c.report_id WHERE r.public_id='r1'
                """);
        assertEquals("user", chat.get("role"));
        assertEquals("遗留问题", chat.get("content"));
        assertEquals(1710000000000L, ((Number) chat.get("client_timestamp")).longValue());

        // One completed legacy run per report plus one shared archive run for orphaned outputs.
        assertEquals(3, count("research_runs"));
        assertEquals(1, count("research_runs WHERE report_id IS NULL"));
        for (Map<String,Object> run : jdbc.queryForList("""
                SELECT report_type, report_source, tone, status, request_options::text AS options,
                       started_at IS NOT NULL AS started, completed_at IS NOT NULL AS completed
                FROM research_runs
                """)) {
            assertEquals("research_report", run.get("report_type"));
            assertEquals("legacy", run.get("report_source"));
            assertEquals("Objective", run.get("tone"));
            assertEquals("completed", run.get("status"));
            assertEquals("legacy_import", JSON.readTree((String) run.get("options")).path("origin").asText());
            assertEquals(true, run.get("started"));
            assertEquals(true, run.get("completed"));
        }
        UUID r1Run = jdbc.queryForObject("""
                SELECT rr.id FROM research_runs rr JOIN reports r ON r.id=rr.report_id WHERE r.public_id='r1'
                """, UUID.class);
        UUID r2Run = jdbc.queryForObject("""
                SELECT rr.id FROM research_runs rr JOIN reports r ON r.id=rr.report_id WHERE r.public_id='r2'
                """, UUID.class);
        UUID orphanRun = jdbc.queryForObject("SELECT id FROM research_runs WHERE report_id IS NULL", UUID.class);
        assertEquals("Orphaned legacy output: outputs/orphan/legacy",
                jdbc.queryForObject("SELECT query FROM research_runs WHERE id=?", String.class, orphanRun));

        // report orderedData -> research_events; .json logs append their events[].data payloads.
        assertEquals(2, events(r1Run).size());
        assertEquals("starting_research", events(r1Run).getFirst().path("content").asText());
        assertEquals(3, events(r2Run).size());
        assertEquals("旧日志", events(r2Run).get(1).path("content").asText());

        // Artifacts: referenced outputs link to their report+run; orphan variants share one run.
        assertEquals(5, count("artifacts"));
        Map<String,Object> r1Artifact = jdbc.queryForMap("""
                SELECT a.kind, a.mime_type, a.report_id, a.run_id, a.legacy_path, a.metadata::text AS metadata,
                       a.content
                FROM artifacts a WHERE a.legacy_path='outputs/r1.md'
                """);
        assertEquals("md", r1Artifact.get("kind"));
        assertEquals("text/markdown", r1Artifact.get("mime_type"));
        assertEquals(jdbc.queryForObject("SELECT id FROM reports WHERE public_id='r1'", UUID.class),
                r1Artifact.get("report_id"));
        assertEquals(r1Run, r1Artifact.get("run_id"));
        assertEquals(root.resolve("outputs/r1.md").toAbsolutePath().normalize().toString(),
                JSON.readTree((String) r1Artifact.get("metadata")).path("imported_from").asText());
        assertArrayEquals(Files.readAllBytes(root.resolve("outputs/r1.md")), (byte[]) r1Artifact.get("content"));
        assertEquals("log", jdbc.queryForObject("SELECT kind FROM artifacts WHERE legacy_path='outputs/logs/r2.json'",
                String.class));
        assertEquals(r2Run, jdbc.queryForObject(
                "SELECT run_id FROM artifacts WHERE legacy_path='outputs/logs/r2.json'", UUID.class));
        for (String orphan : List.of("outputs/orphan/legacy.md", "outputs/orphan/legacy.pdf")) {
            Map<String,Object> row = jdbc.queryForMap(
                    "SELECT report_id, run_id FROM artifacts WHERE legacy_path=?", orphan);
            assertNull(row.get("report_id"));
            assertEquals(orphanRun, row.get("run_id"));
        }

        // Reports.json is idempotent as a whole; every file has its own migration_imports row.
        assertEquals(7, count("migration_imports WHERE status='imported'"));
        assertEquals("2", jdbc.queryForObject("""
                SELECT details->>'reports' FROM migration_imports WHERE source_path=?
                """, String.class, root.resolve("data/reports.json").toAbsolutePath().normalize().toString()));

        // Document uploaded and indexed through the real store.
        Map<String,Object> document = jdbc.queryForMap("SELECT id, filename, status FROM documents");
        assertEquals("legacy-note.txt", document.get("filename"));
        assertEquals("ready", document.get("status"));
        assertEquals(1, count("document_chunks"));
        assertEquals(true, jdbc.queryForObject("SELECT active FROM embedding_profiles", Boolean.class));
        assertEquals("遗留资料内容", new String(jdbc.queryForObject("SELECT content FROM documents WHERE id=?",
                byte[].class, document.get("id")), StandardCharsets.UTF_8));

        // Second run: nothing new, no duplicate events/artifacts/imports.
        Map<String,Integer> before = rowCounts();
        Map<String,Object> second = service.importAll(root);
        assertEquals(before, rowCounts());
        assertEquals(0, second.get("imported_documents"));
        assertEquals(List.of(), second.get("orphan_outputs"));
        assertEquals(2, second.get("reports"));
        assertEquals(5, second.get("output_files"));
    }

    @Test
    void embeddingFailureIsRecordedAndRetriedOnTheNextRun() throws Exception {
        write("my-docs/embed.txt", "需要嵌入的资料");
        when(embeddings.embed(anyList())).thenThrow(new IllegalStateException("embedding provider unavailable"));

        Map<String,Object> failed = service.importAll(root);

        assertEquals(0, failed.get("imported_documents"));
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> failures = (List<Map<String,Object>>) failed.get("failed_documents");
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().get("error").toString().contains("embedding provider unavailable"));
        Map<String,Object> record = jdbc.queryForMap("""
                SELECT status, error FROM migration_imports WHERE source_path=?
                """, root.resolve("my-docs/embed.txt").toAbsolutePath().normalize().toString());
        assertEquals("failed", record.get("status"));
        assertTrue(record.get("error").toString().contains("embedding provider unavailable"));
        assertEquals("failed", jdbc.queryForObject("SELECT status FROM documents", String.class));
        assertEquals(0, count("document_chunks"));

        doAnswer(call -> ((List<?>) call.getArgument(0)).stream()
                .map(text -> new float[]{1, 0, 0}).toList()).when(embeddings).embed(anyList());
        Map<String,Object> retried = service.importAll(root);
        assertEquals(1, retried.get("imported_documents"));
        assertNull(retried.get("failed_documents"));
        assertEquals("imported", jdbc.queryForObject("SELECT status FROM migration_imports WHERE source_path=?",
                String.class, root.resolve("my-docs/embed.txt").toAbsolutePath().normalize().toString()));
        assertEquals("ready", jdbc.queryForObject("SELECT status FROM documents", String.class));
        assertTrue(count("document_chunks") >= 1);
    }

    @Test
    void changedReportsFileReimportsWithoutDuplicatingEvents() throws Exception {
        write("data/reports.json", """
                {"r1":{"question":"改前","answer":"正文一","status":"completed","timestamp":1700000000000,
                  "orderedData":[{"type":"logs","content":"first"}]}}
                """);
        service.importAll(root);
        UUID run = jdbc.queryForObject("""
                SELECT rr.id FROM research_runs rr JOIN reports r ON r.id=rr.report_id
                """, UUID.class);
        assertEquals(1, events(run).size());
        int importsBefore = count("migration_imports");

        write("data/reports.json", """
                {"r1":{"question":"改后","answer":"正文二","status":"completed","timestamp":1700000000000,
                  "orderedData":[{"type":"logs","content":"first"},{"type":"logs","content":"second"}]}}
                """);
        service.importAll(root);

        assertEquals(1, count("research_runs"));
        assertEquals(2, events(run).size());
        assertEquals("second", events(run).get(1).path("content").asText());
        assertEquals("改后", jdbc.queryForObject("SELECT question FROM reports WHERE public_id='r1'", String.class));
        assertEquals("正文二", jdbc.queryForObject("SELECT answer FROM reports WHERE public_id='r1'", String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT jsonb_array_length(ordered_data) FROM reports WHERE public_id='r1'", Integer.class));
        assertEquals(importsBefore + 1, count("migration_imports"));
    }

    @Test
    void retryAfterMissingBookkeepingDoesNotDuplicateDocumentsOrArtifacts() throws Exception {
        write("data/reports.json", """
                {"r1":{"question":"问题","answer":"正文","status":"completed","orderedData":[
                  {"type":"path","content":"report","output":{"md":"/outputs/r1.md"}}]}}
                """);
        write("outputs/r1.md", "# 报告");
        write("my-docs/retry.txt", "重试资料");
        service.importAll(root);
        assertEquals(1, count("research_runs"));
        assertEquals(1, count("artifacts"));
        assertEquals(1, count("documents"));
        assertEquals(1, count("document_chunks"));
        int imports = count("migration_imports");

        // Simulate a crash after the artifact/document writes but before their bookkeeping rows.
        jdbc.update("DELETE FROM migration_imports WHERE source_path LIKE '%r1.md' OR source_path LIKE '%retry.txt'");
        assertEquals(imports - 2, count("migration_imports"));

        Map<String,Object> retried = service.importAll(root);
        assertEquals(1, retried.get("imported_documents"));
        assertEquals(1, count("research_runs"));
        assertEquals(1, count("artifacts"));       // legacy_path upsert keeps one artifact row
        assertEquals(1, count("documents"));       // documents(filename,sha256) upsert keeps one row
        assertEquals(1, count("document_chunks")); // replace() clears old chunks before rewriting
        assertEquals("ready", jdbc.queryForObject("SELECT status FROM documents", String.class));
        assertEquals(imports, count("migration_imports"));
        assertEquals(1, count("reports"));
    }

    private void fixture() throws IOException {
        write("data/reports.json", """
                {
                  "r1": {
                    "question": "第一份遗留报告",
                    "answer": "# 正文一",
                    "status": "completed",
                    "timestamp": 1700000000000,
                    "orderedData": [
                      {"type":"logs","content":"starting_research","output":"开始"},
                      {"type":"path","content":"report","output":{"md":"/outputs/r1.md","pdf":"outputs/r1.pdf"}}
                    ],
                    "chatMessages": [{"role":"user","content":"遗留问题","timestamp":1710000000000}]
                  },
                  "r2": {
                    "question": "第二份遗留报告",
                    "answer": "# 正文二",
                    "status": "completed",
                    "timestamp": 1700000001000,
                    "orderedData": [
                      {"type":"path","content":"report","output":{"log":"/outputs/logs/r2.json"}}
                    ],
                    "chatMessages": []
                  }
                }
                """);
        write("outputs/r1.md", "# 报告一");
        write("outputs/r1.pdf", "pdf-one");
        write("outputs/logs/r2.json", """
                {"events":[
                  {"type":"logs","content":"legacy_log","data":{"type":"logs","content":"旧日志","output":"进度"}},
                  {"type":"path","content":"artifact","data":{"type":"path","content":"artifact","output":{"md":"x"}}},
                  "not-an-object",
                  {"data":"not-an-object"}
                ]}
                """);
        write("outputs/orphan/legacy.md", "# 孤儿一");
        write("outputs/orphan/legacy.pdf", "orphan-pdf");
        write("my-docs/legacy-note.txt", "遗留资料内容");
    }

    private void write(String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }

    private List<JsonNode> events(UUID run) throws IOException {
        List<String> payloads = jdbc.queryForList(
                "SELECT payload::text FROM research_events WHERE run_id=? ORDER BY id", String.class, run);
        List<JsonNode> result = new java.util.ArrayList<>();
        for (String payload : payloads) result.add(JSON.readTree(payload));
        return result;
    }

    private Map<String,Integer> rowCounts() {
        Map<String,Integer> counts = new LinkedHashMap<>();
        for (String table : List.of("reports", "research_runs", "research_events", "chat_messages", "artifacts",
                "migration_imports", "documents", "document_chunks", "embedding_profiles")) {
            counts.put(table, count(table));
        }
        return counts;
    }
}
