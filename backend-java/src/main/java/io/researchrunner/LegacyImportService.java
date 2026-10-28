package io.researchrunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.researchrunner.document.DocumentIndex;
import io.researchrunner.document.DocumentStore;
import io.researchrunner.report.ArtifactStore;
import io.researchrunner.report.ReportWriteService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * A4-11: import {@code data/reports.json}, {@code outputs/} and {@code my-docs/} into PostgreSQL.
 *
 * <p>This is a Java port of {@code scripts/migrate_legacy_storage.py}. It keeps the Python
 * semantics: the whole {@code reports.json} file is idempotent by {@code source_path + sha256},
 * every output file and document is idempotent by its own path and hash, referenced outputs are
 * attached to the importing report's run, unreferenced ("orphaned") outputs share one archive run
 * per {@code orphan_group_key}, and documents are uploaded + indexed through the normal services.
 *
 * <p>One deliberate robustness improvement over the Python script: a {@code migration_imports}
 * row is only a skip marker when its status is {@code imported}. A {@code failed} document import
 * (for example because embeddings were unavailable) is retried on the next run instead of being
 * skipped forever.
 */
@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class LegacyImportService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String,String> KINDS = Map.ofEntries(
            Map.entry(".md", "md"), Map.entry(".pdf", "pdf"), Map.entry(".docx", "docx"),
            Map.entry(".json", "log"), Map.entry(".png", "image"), Map.entry(".jpg", "image"),
            Map.entry(".jpeg", "image"), Map.entry(".webp", "image"));
    private static final Map<String,String> MIME_TYPES = Map.ofEntries(
            Map.entry(".md", "text/markdown"), Map.entry(".pdf", "application/pdf"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".doc", "application/msword"), Map.entry(".json", "application/json"),
            Map.entry(".txt", "text/plain"), Map.entry(".csv", "text/csv"),
            Map.entry(".html", "text/html"), Map.entry(".htm", "text/html"),
            Map.entry(".png", "image/png"), Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"), Map.entry(".webp", "image/webp"),
            Map.entry(".gif", "image/gif"), Map.entry(".svg", "image/svg+xml"),
            Map.entry(".zip", "application/zip"));

    private final ReportWriteService writes;
    private final LegacyWriteMapper mapper;
    private final ArtifactStore artifacts;
    private final DocumentStore documents;
    private final DocumentIndex index;
    private final ObjectMapper json;

    public LegacyImportService(ReportWriteService writes, LegacyWriteMapper mapper, ArtifactStore artifacts,
            DocumentStore documents, DocumentIndex index, ObjectMapper json) {
        this.writes = writes;
        this.mapper = mapper;
        this.artifacts = artifacts;
        this.documents = documents;
        this.index = index;
        this.json = json;
    }

    /** Per-document outcome; failures keep the CLI exit code non-zero and are reported verbatim. */
    public record DocumentImport(int imported, List<Map<String,Object>> failures) { }

    /**
     * Runs the whole import and returns the JSON-ready summary. Never called for {@code --dry-run};
     * the CLI keeps the read-only inventory path database-free.
     */
    public Map<String,Object> importAll(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path base = root.toAbsolutePath().normalize();
        Path reportsPath = base.resolve("data").resolve("reports.json");
        Path outputRoot = base.resolve("outputs");
        Path documentRoot = base.resolve("my-docs");
        JsonNode reports = loadReports(reportsPath);
        Map<String,String> references = referencedOutputs(reports);

        Map<String,Object> summary = new LinkedHashMap<>();
        summary.put("reports", reports.size());
        summary.put("referenced_outputs", references.size());
        summary.put("output_files", countFiles(outputRoot));
        summary.put("documents", countFiles(documentRoot));
        summary.put("dry_run", false);

        Map<String,UUID> runIds = importReports(reportsPath, reports);
        List<String> orphans = importOutputs(outputRoot, references, runIds);
        DocumentImport importedDocuments = importDocuments(documentRoot);
        summary.put("orphan_outputs", orphans);
        summary.put("imported_documents", importedDocuments.imported());
        if (!importedDocuments.failures().isEmpty()) summary.put("failed_documents", importedDocuments.failures());
        return summary;
    }

    /** Python {@code import_reports}; returns the public id → run id map used by referenced outputs. */
    Map<String,UUID> importReports(Path reportsPath, JsonNode reports) throws IOException {
        Map<String,UUID> runIds = new LinkedHashMap<>();
        if (!Files.isRegularFile(reportsPath)) return runIds;
        String sourcePath = absolute(reportsPath);
        String sourceHash = DocumentStore.hash(Files.readAllBytes(reportsPath));
        boolean already = imported(sourcePath, sourceHash);
        var fields = reports.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String publicId = entry.getKey();
            JsonNode report = entry.getValue();
            if (!report.isObject()) {
                throw new IllegalArgumentException("reports.json 条目必须是 JSON 对象：" + publicId);
            }
            if (!already) {
                ObjectNode input = (ObjectNode) report.deepCopy();
                input.put("id", publicId);
                writes.upsert(input);
            }
            UUID runId = ensureReportRun(publicId, queryFor(report));
            runIds.put(publicId, runId);
            if (!already) appendMissingEvents(runId, report.path("orderedData"));
        }
        if (!already) {
            mapper.recordImport(UUID.randomUUID(), sourcePath, sourceHash, "imported", null,
                    details(Map.of("reports", reports.size())));
        }
        return runIds;
    }

    /**
     * Python {@code import_outputs}: each file is checked against {@code migration_imports} first;
     * referenced files reuse their report's run, orphaned files share a per-group archive run.
     */
    List<String> importOutputs(Path outputRoot, Map<String,String> references, Map<String,UUID> runIds)
            throws IOException {
        List<String> orphans = new ArrayList<>();
        if (!Files.isDirectory(outputRoot)) return orphans;
        Path parent = outputRoot.getParent() == null ? outputRoot : outputRoot.getParent();
        Map<String,UUID> orphanRuns = new LinkedHashMap<>();
        for (Path path : sortedFiles(outputRoot)) {
            String relative = normalizePath(parent.relativize(path).toString());
            if (relative.isEmpty()) relative = path.getFileName().toString();
            if (relative.equals("..") || relative.startsWith("../") || relative.contains("/../")) {
                throw new IllegalStateException("输出文件相对路径越界：" + relative);
            }
            byte[] content = Files.readAllBytes(path);
            String sourcePath = absolute(path);
            String sourceHash = DocumentStore.hash(content);
            if (imported(sourcePath, sourceHash)) continue;

            String publicId = references.get(relative);
            UUID runId = publicId == null ? null : runIds.get(publicId);
            if (runId == null) {
                String groupKey = orphanGroupKey(relative);
                runId = orphanRuns.get(groupKey);
                if (runId == null) {
                    runId = ensureOrphanRun(groupKey);
                    orphanRuns.put(groupKey, runId);
                }
                orphans.add(relative);
            }
            if (suffix(path).equals(".json")) appendLogEvents(path, runId);
            artifacts.save(content, path.getFileName().toString(), kindFor(path), mimeTypeFor(path),
                    publicId, runId, relative, Map.of("imported_from", sourcePath));
            mapper.recordImport(UUID.randomUUID(), sourcePath, sourceHash, "imported", null,
                    details(Map.of("legacy_path", relative)));
        }
        return orphans;
    }

    /**
     * Python {@code import_documents}: upload + index; an indexing failure is recorded as
     * {@code migration_imports.status=failed} with the error and surfaced in the summary.
     */
    DocumentImport importDocuments(Path documentRoot) throws IOException {
        int imported = 0;
        List<Map<String,Object>> failures = new ArrayList<>();
        for (Path path : sortedFiles(documentRoot)) {
            byte[] content = Files.readAllBytes(path);
            String sourcePath = absolute(path);
            String sourceHash = DocumentStore.hash(content);
            if (imported(sourcePath, sourceHash)) continue;
            try {
                JsonNode document = documents.upload(path.getFileName().toString(), mimeTypeFor(path), content);
                UUID documentId = UUID.fromString(document.path("id").asText());
                index.index(documentId);
                mapper.recordImport(UUID.randomUUID(), sourcePath, sourceHash, "imported", null,
                        details(Map.of("document_id", documentId.toString())));
                imported++;
            } catch (Exception error) {
                String message = describe(error);
                mapper.recordImport(UUID.randomUUID(), sourcePath, sourceHash, "failed", truncate(message, 4000),
                        "{}");
                Map<String,Object> failure = new LinkedHashMap<>();
                failure.put("path", sourcePath);
                failure.put("error", message);
                failures.add(failure);
            }
        }
        return new DocumentImport(imported, failures);
    }

    /** Mirrors {@code ensure_imported_run}: reuse the report's run, otherwise insert a deterministic one. */
    private UUID ensureReportRun(String publicId, String query) {
        String reportId = mapper.reportId(publicId);
        UUID reportUuid = reportId == null ? null : UUID.fromString(reportId);
        if (reportUuid != null) {
            String existing = mapper.runForReport(reportUuid);
            if (existing != null) return UUID.fromString(existing);
        }
        UUID candidate = reportRunId(publicId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (reportUuid == null) {
            mapper.insertOrphanRun(candidate, query, now);
        } else {
            mapper.insertImportedRun(candidate, reportUuid, query, now);
        }
        String stored = reportUuid == null ? mapper.runById(candidate) : mapper.runForReport(reportUuid);
        if (stored == null) throw new IllegalStateException("无法创建遗留内容的研究运行记录：" + publicId);
        return UUID.fromString(stored);
    }

    private UUID ensureOrphanRun(String groupKey) {
        UUID candidate = orphanRunId(groupKey);
        mapper.insertOrphanRun(candidate, "Orphaned legacy output: " + groupKey, OffsetDateTime.now(ZoneOffset.UTC));
        String stored = mapper.runById(candidate);
        if (stored == null) throw new IllegalStateException("无法创建孤儿输出的研究运行记录：" + groupKey);
        return UUID.fromString(stored);
    }

    /**
     * Appends {@code orderedData} events to an imported run. The event count is used as a resume
     * point so a crash between event inserts and the {@code migration_imports} record cannot
     * duplicate the already-written prefix, and a real research run is never polluted.
     */
    private void appendMissingEvents(UUID runId, JsonNode events) {
        if (!events.isArray() || events.size() == 0) return;
        if (mapper.importedRun(runId) == 0) return;
        long present = mapper.eventCount(runId);
        for (long position = present; position < events.size(); position++) {
            JsonNode event = events.get((int) position);
            if (event.isObject()) appendEvent(runId, event);
        }
    }

    private void appendEvent(UUID runId, JsonNode payload) {
        String type = text(payload.get("type"));
        if (type.isEmpty()) type = "event";
        JsonNode content = payload.get("content");
        mapper.appendEvent(runId, truncate(type, 80),
                content == null || content.isNull() ? null : truncate(text(content), 160), payload.toString());
    }

    private void appendLogEvents(Path path, UUID runId) {
        try {
            JsonNode log = JSON.readTree(Files.readAllBytes(path));
            JsonNode events = log.path("events");
            if (!events.isArray()) return;
            for (JsonNode item : events) {
                JsonNode payload = item.path("data");
                if (payload.isObject()) appendEvent(runId, payload);
            }
        } catch (IOException ignored) {
            // Python silently skips unreadable/invalid .json logs.
        }
    }

    private boolean imported(String sourcePath, String sha256) throws IOException {
        String record = mapper.findImport(sourcePath, sha256);
        if (record == null) return false;
        return "imported".equals(JSON.readTree(record).path("status").asText());
    }

    private static String queryFor(JsonNode report) {
        JsonNode question = report.get("question");
        if (question == null || question.isNull() || (question.isValueNode() && question.asText().isEmpty())) {
            return "Legacy report";
        }
        return text(question);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return "";
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        String simple = error.getClass().getSimpleName();
        return message == null || message.isBlank() ? simple : simple + ": " + message;
    }

    private static String truncate(String value, int length) {
        return value == null ? null : value.length() <= length ? value : value.substring(0, length);
    }

    private static String details(Map<String,?> values) {
        return JSON.valueToTree(values).toString();
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /** Python {@code path.suffix.lower()}; includes the leading dot, empty when absent. */
    static String suffix(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- Python parity helpers

    /** Python {@code load_reports}: missing/invalid shapes become an empty object, bad JSON fails. */
    public static JsonNode loadReports(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return JSON.createObjectNode();
        JsonNode value = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        return value != null && value.isObject() ? value : JSON.createObjectNode();
    }

    /** Python {@code referenced_outputs}: only {@code type=path} events, values URL-decoded. */
    public static Map<String,String> referencedOutputs(JsonNode reports) {
        Map<String,String> result = new LinkedHashMap<>();
        var entries = reports.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            JsonNode events = entry.getValue().path("orderedData");
            if (!events.isArray()) continue;
            for (JsonNode event : events) {
                if (!event.isObject() || !"path".equals(event.path("type").asText())) continue;
                JsonNode output = event.path("output");
                if (!output.isObject()) continue;
                var values = output.fields();
                while (values.hasNext()) {
                    JsonNode value = values.next().getValue();
                    if (!value.isTextual() || value.asText().isEmpty()) continue;
                    String normalized = normalizePath(unquote(value.asText()));
                    if (!normalized.isEmpty()) result.put(normalized, entry.getKey());
                }
            }
        }
        return result;
    }

    /** Python {@code normalize_path}: forward slashes, leading slash removal, {@code /outputs/} truncation. */
    public static String normalizePath(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/').strip();
        int start = 0;
        while (start < normalized.length() && normalized.charAt(start) == '/') start++;
        normalized = normalized.substring(start);
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        int marker = normalized.toLowerCase(Locale.ROOT).lastIndexOf("/outputs/");
        if (marker >= 0) normalized = normalized.substring(marker + 1);
        return normalized;
    }

    /** Python {@code urllib.parse.unquote}: percent-escapes are decoded, {@code +} stays literal. */
    public static String unquote(String value) {
        StringBuilder result = new StringBuilder(value.length());
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current == '%' && index + 2 < value.length()
                    && Character.digit(value.charAt(index + 1), 16) >= 0
                    && Character.digit(value.charAt(index + 2), 16) >= 0) {
                pending.write(Character.digit(value.charAt(index + 1), 16) * 16
                        + Character.digit(value.charAt(index + 2), 16));
                index += 3;
            } else {
                flush(pending, result);
                result.append(current);
                index++;
            }
        }
        flush(pending, result);
        return result.toString();
    }

    private static void flush(ByteArrayOutputStream pending, StringBuilder result) {
        if (pending.size() == 0) return;
        result.append(new String(pending.toByteArray(), StandardCharsets.UTF_8));
        pending.reset();
    }

    /** Python {@code orphan_group_key}: {@code Path(...).with_suffix("")}, forward slashes retained. */
    public static String orphanGroupKey(String relativePath) {
        String normalized = normalizePath(relativePath);
        int slash = normalized.lastIndexOf('/');
        String name = normalized.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return normalized.substring(0, slash + 1) + name;
    }

    /** Python artifact kind mapping used by {@code import_outputs}. */
    public static String kindFor(Path path) {
        return KINDS.getOrDefault(suffix(path), "legacy");
    }

    /** Python {@code mimetypes.guess_type(...)[0]}; {@code null} becomes application/octet-stream. */
    public static String mimeTypeFor(Path path) {
        return MIME_TYPES.get(suffix(path));
    }

    static UUID reportRunId(String publicId) {
        return deterministic("legacy_import:report:" + publicId);
    }

    static UUID orphanRunId(String groupKey) {
        return deterministic("legacy_import:orphan:" + groupKey);
    }

    private static UUID deterministic(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    /** Counts regular files below {@code directory} (dry-run inventory + summary parity). */
    public static int countFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> files = Files.walk(directory)) {
            return (int) files.filter(Files::isRegularFile).count();
        }
    }

    /** Deterministic iteration order: normalized relative path, independent of filesystem order. */
    static List<Path> sortedFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
    }
}
