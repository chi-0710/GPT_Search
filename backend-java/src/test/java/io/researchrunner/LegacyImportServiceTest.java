package io.researchrunner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.researchrunner.document.DocumentIndex;
import io.researchrunner.document.DocumentStore;
import io.researchrunner.report.ArtifactStore;
import io.researchrunner.report.ReportWriteService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** A4-11 service-level idempotency, orphan grouping and failure-recording behaviour (mocked stores). */
class LegacyImportServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path root;

    private final ReportWriteService writes = mock(ReportWriteService.class);
    private final LegacyWriteMapper mapper = mock(LegacyWriteMapper.class);
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final DocumentIndex index = mock(DocumentIndex.class);

    private LegacyImportService service() {
        return new LegacyImportService(writes, mapper, artifacts, documents, index, JSON);
    }

    private void file(String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Test
    void repeatRunSkipsAlreadyImportedReportsOutputsAndDocuments() throws Exception {
        file("data/reports.json", """
                {"r1":{"question":"问题","answer":"正文","orderedData":[]}}
                """);
        file("outputs/r1.md", "# 报告");
        file("my-docs/资料.txt", "资料内容");
        UUID report = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        when(mapper.findImport(anyString(), anyString())).thenReturn("{\"status\":\"imported\"}");
        when(mapper.reportId("r1")).thenReturn(report.toString());
        when(mapper.runForReport(report)).thenReturn(run.toString());

        Map<String,Object> summary = service().importAll(root);

        assertEquals(1, summary.get("reports"));
        assertEquals(0, summary.get("referenced_outputs"));
        assertEquals(1, summary.get("output_files"));
        assertEquals(1, summary.get("documents"));
        assertEquals(0, summary.get("imported_documents"));
        assertEquals(false, summary.get("dry_run"));
        verify(writes, never()).upsert(any());
        verify(mapper, never()).insertImportedRun(any(), any(), anyString(), any());
        verify(mapper, never()).insertOrphanRun(any(), anyString(), any());
        verify(mapper, never()).appendEvent(any(), anyString(), any(), anyString());
        verify(mapper, never()).recordImport(any(), anyString(), anyString(), anyString(), any(), anyString());
        verifyNoInteractions(artifacts, documents, index);
    }

    @Test
    void orphanFormatVariantsShareOneRunWhileReferencedOutputUsesReportRun() throws Exception {
        file("data/reports.json", """
                {"r1":{"question":"问题","answer":"正文","orderedData":[
                  {"type":"path","content":"report","output":{"md":"/outputs/r1.md"}}]}}
                """);
        file("outputs/r1.md", "# 报告");
        file("outputs/orphan/legacy.md", "# 孤儿 Markdown");
        file("outputs/orphan/legacy.pdf", "pdf-bytes");
        UUID report = UUID.randomUUID();
        UUID reportRun = UUID.randomUUID();
        UUID orphanRun = LegacyImportService.orphanRunId("outputs/orphan/legacy");
        when(mapper.reportId("r1")).thenReturn(report.toString());
        when(mapper.runForReport(report)).thenReturn(null, reportRun.toString());
        when(mapper.importedRun(reportRun)).thenReturn(1);
        when(mapper.eventCount(reportRun)).thenReturn(0L);
        when(mapper.runById(any(UUID.class))).thenAnswer(call -> call.getArgument(0).toString());

        Map<String,Object> summary = service().importAll(root);

        assertEquals(1, summary.get("reports"));
        assertEquals(1, summary.get("referenced_outputs"));
        assertEquals(3, summary.get("output_files"));
        assertEquals(List.of("outputs/orphan/legacy.md", "outputs/orphan/legacy.pdf"), summary.get("orphan_outputs"));
        verify(writes).upsert(argThat(input -> "r1".equals(input.path("id").asText())));
        verify(mapper).insertImportedRun(any(), eq(report), eq("问题"), any());
        verify(mapper).insertOrphanRun(eq(orphanRun), eq("Orphaned legacy output: outputs/orphan/legacy"), any());
        verify(mapper).appendEvent(eq(reportRun), eq("path"), eq("report"), anyString());
        verify(artifacts).save(any(), eq("r1.md"), eq("md"), eq("text/markdown"), eq("r1"), eq(reportRun),
                eq("outputs/r1.md"), argThat(metadata -> ((String) metadata.get("imported_from")).endsWith("r1.md")));
        verify(artifacts).save(any(), eq("legacy.md"), eq("md"), eq("text/markdown"), isNull(), eq(orphanRun),
                eq("outputs/orphan/legacy.md"), anyMap());
        verify(artifacts).save(any(), eq("legacy.pdf"), eq("pdf"), eq("application/pdf"), isNull(), eq(orphanRun),
                eq("outputs/orphan/legacy.pdf"), anyMap());
        verify(mapper, times(4)).recordImport(any(), anyString(), anyString(), eq("imported"), isNull(), anyString());
    }

    @Test
    void documentIndexFailureIsRecordedAsFailedAndRetriedOnTheNextRun() throws Exception {
        file("my-docs/资料.txt", "需要索引的资料");
        UUID documentId = UUID.randomUUID();
        ObjectNode uploaded = JSON.createObjectNode().put("id", documentId.toString());
        when(documents.upload(anyString(), any(), any())).thenReturn(uploaded);
        doThrow(new IllegalStateException("embedding provider unavailable")).when(index).index(documentId);

        Map<String,Object> summary = service().importAll(root);

        assertEquals(0, summary.get("imported_documents"));
        assertEquals(0, summary.get("reports"));
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> failures = (List<Map<String,Object>>) summary.get("failed_documents");
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().get("error").toString().contains("embedding provider unavailable"));
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(mapper).recordImport(any(), anyString(), anyString(), status.capture(), error.capture(), eq("{}"));
        assertEquals("failed", status.getValue());
        assertTrue(error.getValue().contains("embedding provider unavailable"));
        verify(mapper, never()).recordImport(any(), anyString(), anyString(), eq("imported"), any(), anyString());

        doNothing().when(index).index(documentId);
        Map<String,Object> retried = service().importAll(root);
        assertEquals(1, retried.get("imported_documents"));
        assertNull(retried.get("failed_documents"));
        verify(mapper).recordImport(any(), anyString(), anyString(), eq("imported"), isNull(),
                contains("document_id"));
    }

    @Test
    void reportEventsResumeFromThePersistedEventCount() throws Exception {
        file("data/reports.json", """
                {"r1":{"question":"问题","orderedData":[
                  {"type":"logs","content":"one"},
                  {"type":"logs","content":"two"},
                  {"type":"logs","content":"three"}]}}
                """);
        UUID report = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        when(mapper.reportId("r1")).thenReturn(report.toString());
        when(mapper.runForReport(report)).thenReturn(run.toString());
        when(mapper.importedRun(run)).thenReturn(1);
        when(mapper.eventCount(run)).thenReturn(1L);

        service().importAll(root);

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).appendEvent(eq(run), eq("logs"), anyString(), payloads.capture());
        assertEquals(2, payloads.getAllValues().size());
        assertTrue(payloads.getAllValues().get(0).contains("\"two\""));
        assertTrue(payloads.getAllValues().get(1).contains("\"three\""));
        verify(mapper).recordImport(any(), anyString(), anyString(), eq("imported"), isNull(), anyString());
    }

    @Test
    void eventsAreNotInjectedIntoARealResearchRun() throws Exception {
        file("data/reports.json", """
                {"r1":{"question":"问题","orderedData":[{"type":"logs","content":"one"}]}}
                """);
        UUID report = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        when(mapper.reportId("r1")).thenReturn(report.toString());
        when(mapper.runForReport(report)).thenReturn(run.toString());
        when(mapper.importedRun(run)).thenReturn(0);
        when(mapper.eventCount(run)).thenReturn(0L);

        service().importAll(root);

        verify(mapper, never()).appendEvent(any(), anyString(), any(), anyString());
        verify(mapper).recordImport(any(), anyString(), anyString(), eq("imported"), isNull(), anyString());
    }

    @Test
    void normalizesPathsLikeThePythonImporter() {
        assertEquals("outputs/r1.md", LegacyImportService.normalizePath("\\outputs//r1.md"));
        assertEquals("outputs/r1.md", LegacyImportService.normalizePath("/outputs/r1.md"));
        assertEquals("outputs/report.md", LegacyImportService.normalizePath("F:/root/outputs/report.md"));
        assertEquals("outputs/x.md", LegacyImportService.normalizePath("https://host/static/outputs/x.md"));
        assertEquals("OUTPUTS/x.md", LegacyImportService.normalizePath("a/OUTPUTS/x.md"));
        assertEquals("", LegacyImportService.normalizePath(null));
        assertEquals("", LegacyImportService.normalizePath("   "));

        assertEquals("a+b c中", LegacyImportService.unquote("a+b%20c%E4%B8%AD"));
        assertEquals("%ZZ", LegacyImportService.unquote("%ZZ"));
        assertEquals("\uFFFD", LegacyImportService.unquote("%E4%B8"));

        assertEquals("outputs/a.tar", LegacyImportService.orphanGroupKey("outputs/a.tar.gz"));
        assertEquals("outputs/a", LegacyImportService.orphanGroupKey("outputs/a.md"));
        assertEquals("outputs/noext", LegacyImportService.orphanGroupKey("outputs/noext"));
        assertEquals("outputs/.hidden", LegacyImportService.orphanGroupKey("outputs/.hidden"));

        assertEquals("md", LegacyImportService.kindFor(Path.of("x.MD")));
        assertEquals("log", LegacyImportService.kindFor(Path.of("x.json")));
        assertEquals("image", LegacyImportService.kindFor(Path.of("x.jpeg")));
        assertEquals("legacy", LegacyImportService.kindFor(Path.of("x.bin")));
        assertEquals("image/png", LegacyImportService.mimeTypeFor(Path.of("x.png")));
        assertNull(LegacyImportService.mimeTypeFor(Path.of("x.bin")));

        // Python parity: normalize_path never resolves "..", and artifact legacy paths are never absolute.
        assertEquals("outputs/../secret.md", LegacyImportService.normalizePath("outputs/../secret.md"));
        assertFalse(LegacyImportService.normalizePath("/abs/outputs/x.md").startsWith("/"));
        assertFalse(LegacyImportService.normalizePath("C:\\abs\\outputs\\x.md").contains(":"));
    }

    @Test
    void referencedOutputsOnlyUsePathEventsAndDecodePercentEscapes() throws Exception {
        JsonNode reports = JSON.readTree("""
                {
                  "r1":{"orderedData":[
                    {"type":"path","output":{"md":"/outputs/a%20b.md","pdf":"/outputs/c.pdf","count":3}},
                    {"type":"logs","output":{"md":"/outputs/ignored.md"}},
                    "not-an-object"
                  ]},
                  "r2":{"orderedData":[{"type":"path","output":{"docx":"outputs/d.docx"}}]},
                  "r3":{}
                }
                """);
        Map<String,String> references = LegacyImportService.referencedOutputs(reports);
        assertEquals(Map.of("outputs/a b.md", "r1", "outputs/c.pdf", "r1", "outputs/d.docx", "r2"), references);
    }
}
