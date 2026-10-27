package io.researchrunner.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.infrastructure.ResourceMapper;
import io.researchrunner.infrastructure.StoredFile;
import io.researchrunner.report.ResourceService;
import io.researchrunner.report.HistoryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResourceControllerTest {
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private ResourceMapper mapper;
    private HistoryService history;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mapper = mock(ResourceMapper.class);
        history = mock(HistoryService.class);
        var service = new ResourceService(mapper, history, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new ResourceController(service))
                .setControllerAdvice(new ApiErrors()).build();
        when(mapper.runByReport("legacy")).thenReturn("""
            {"id":"00000000-0000-0000-0000-000000000101","research_id":"legacy",
             "report_type":"deep","query":"问题","costs":{},"started_at":null}
            """);
    }

    @Test
    void resolvesLegacyIdsWithoutRewritingStoredMode() throws Exception {
        mvc.perform(get("/api/research-runs/legacy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.run.report_type").value("deep"));
        mvc.perform(get("/api/research-runs/missing/events")).andExpect(status().isNotFound());
        mvc.perform(get("/api/reports/legacy/evidence")).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence").isEmpty());
    }

    @Test
    void eventPaginationValidatesBeforeQuerying() throws Exception {
        mvc.perform(get("/api/research-runs/legacy/events").param("after_id", "-1"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/research-runs/legacy/events").param("limit", "2001"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(mapper);
    }

    @Test
    void rendersEscapedSnapshotsAfterOriginalDeletion() throws Exception {
        when(mapper.evidence(RUN)).thenReturn(List.of("""
            {"number":1,"source_type":"local","title":"<script>bad</script>",
             "content":"<img src=x onerror=alert(1)>","original_available":false}
            """));
        mvc.perform(get("/api/research-runs/legacy/evidence/1/view"))
                .andExpect(status().isOk()).andExpect(header().exists("Content-Security-Policy"))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<img"))))
                .andExpect(content().string(containsString("原文件已删除")));
        mvc.perform(get("/api/research-runs/legacy/evidence/2")).andExpect(status().isNotFound());
    }

    @Test
    void webReferencesRejectExecutableOrInvalidUrls() throws Exception {
        for (String url : List.of("javascript:alert(1)", "file:///tmp/a", "https://@", "https://example.com/a b")) {
            when(mapper.evidence(RUN)).thenReturn(List.of(new ObjectMapper().writeValueAsString(
                    java.util.Map.of("number", 1, "source_type", "web", "url", url))));
            mvc.perform(get("/api/research-runs/legacy/evidence/1/view"))
                    .andExpect(status().isUnprocessableEntity());
        }
        when(mapper.evidence(RUN)).thenReturn(List.of("""
            {"number":1,"source_type":"web","url":"https://example.com/page?a=1"}
            """));
        mvc.perform(get("/api/research-runs/legacy/evidence/1/view"))
                .andExpect(status().isFound()).andExpect(header().string("Location", "https://example.com/page?a=1"));
    }

    @Test
    void onlyVerifiedPdfDocumentsCanBeDisplayedInline() throws Exception {
        when(mapper.document(RUN)).thenReturn(new StoredFile("伪装.pdf", "application/pdf", "hash", "<html>".getBytes()));
        mvc.perform(get("/api/documents/" + RUN + "/content").param("download", "false"))
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        when(mapper.document(RUN)).thenReturn(new StoredFile("原文.pdf", "application/pdf", "hash", "%PDF-1.7".getBytes()));
        mvc.perform(get("/api/documents/" + RUN + "/content").param("download", "false"))
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline;")));
    }

    @Test
    void downloadsStoredBytesAndReturns404ForInvalidIds() throws Exception {
        byte[] content = {0, 1, 2, 3};
        when(mapper.artifact(RUN)).thenReturn(new StoredFile("报告.docx", "application/octet-stream", "abc", content));
        mvc.perform(get("/api/artifacts/" + RUN).param("download", "true"))
                .andExpect(status().isOk()).andExpect(content().bytes(content))
                .andExpect(header().string("ETag", "\"abc\""))
                .andExpect(header().string("Content-Length", "4"));
        mvc.perform(get("/api/artifacts/not-uuid")).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/not-uuid/content")).andExpect(status().isNotFound());
    }

    @Test
    void legacyDownloadUsesDatabaseKeyRatherThanDiskPath() throws Exception {
        when(mapper.legacyArtifact("old/report.pdf", "outputs/old/report.pdf"))
                .thenReturn(new StoredFile("report.pdf", "application/pdf", "hash", "%PDF-".getBytes()));
        mvc.perform(get("/outputs/old/report.pdf")).andExpect(status().isOk());
        verify(mapper).legacyArtifact("old/report.pdf", "outputs/old/report.pdf");
    }

    @Test
    void logDownloadsContainAllEventBatches() throws Exception {
        List<String> batch = java.util.stream.LongStream.rangeClosed(1, 2000)
                .mapToObj(i -> "{\"id\":" + i + ",\"createdAt\":\"2026-01-01T00:00:00Z\"}").toList();
        when(mapper.events(RUN, 0, 2000)).thenReturn(batch);
        when(mapper.events(RUN, 2000, 2000)).thenReturn(List.of("{\"id\":2001,\"createdAt\":null}"));
        when(history.report("legacy")).thenReturn(new ObjectMapper().readTree("{\"answer\":\"报告正文\"}"));
        mvc.perform(get("/api/research-runs/legacy/log")).andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2001))
                .andExpect(jsonPath("$.content.report").value("报告正文"));
    }
}
