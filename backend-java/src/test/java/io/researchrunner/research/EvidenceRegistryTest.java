package io.researchrunner.research;

import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceRegistryTest {
    @Test
    void parallelChaptersShareStableEvidenceIdsAndSnapshotsAreImmutable() throws Exception {
        var registry = new EvidenceRegistry(UUID.randomUUID());
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<List<Integer>>>();
            for (int i = 0; i < 20; i++) tasks.add(executor.submit(() -> registry.register(
                    List.of(Map.of("url", "https://example.com", "content", "共同证据")), "web")));
            for (var task : tasks) assertEquals(List.of(1), task.get());
        }
        registry.context(List.of(), List.of(1), 12, 12);
        assertEquals(1, registry.snapshots().size());
        assertThrows(UnsupportedOperationException.class, () -> registry.snapshots().getFirst().put("content", "changed"));
        assertTrue(registry.render("正文 [E1] [E99]").contains("[引用不可核验]"));
        assertFalse(registry.warnings().isEmpty());
    }

    @Test
    void unicodeBudgetsPreserveWholeCharactersAndLocalEvidenceLinks() {
        UUID run = UUID.randomUUID();
        var registry = new EvidenceRegistry(run);
        var local = registry.register(List.of(Map.of("document_id", "doc", "content", "😀中文内容", "title", "资料", "page", 2)), "document");
        registry.context(local, List.of(), 1, 1);
        assertEquals("😀中", registry.snapshots().getFirst().get("content"));
        assertTrue(registry.render("[E1]").contains("/api/research-runs/" + run + "/evidence/1/view"));
    }

    @Test
    void emptyEvidenceFailsAndInventedLinksStayInvalid() {
        var registry = new EvidenceRegistry(UUID.randomUUID());
        assertTrue(registry.register(List.of(Map.of("url", "file:///etc/passwd", "content", "data")), "web").isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.context(List.of(), List.of(), 12, 12));
        var ids = registry.register(List.of(Map.of("url", "https://example.com/中文(1)", "content", "source")), "web");
        registry.context(List.of(), ids, 12, 12);
        String output = registry.render("[E1] [fake](https://unverified.example) [目录](#section)");
        assertTrue(output.contains("%28"));
        assertTrue(output.contains("[引用不可核验]"));
        assertTrue(output.contains("[目录](#section)"));
    }

    @Test
    void invalidSelectedDocumentNeverExpandsToAll() {
        List<Map<String, Object>> docs = List.of(Map.of("id", "one", "status", "ready", "embedding_profile_id", "profile"));
        assertThrows(IllegalArgumentException.class, () -> DocumentScope.freeze(docs, "profile", "selected", List.of("missing"), "hybrid"));
        assertThrows(IllegalArgumentException.class, () -> DocumentScope.freeze(docs, "other", "all", List.of(), "local"));
        assertEquals(List.of("one"), DocumentScope.freeze(docs, "profile", "selected", List.of("one", "one"), "hybrid"));
        assertTrue(DocumentScope.freeze(List.of(), "profile", "all", List.of(), "hybrid").isEmpty());
    }
}
