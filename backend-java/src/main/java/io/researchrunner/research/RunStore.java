package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.researchrunner.infrastructure.ReportWriteMapper;
import io.researchrunner.infrastructure.RunWriteMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic run lifecycle; completed and failed states are terminal and mutually exclusive. */
@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled", havingValue="true")
@Transactional
public class RunStore {
    private final RunWriteMapper runs;
    private final ReportWriteMapper reports;
    private final ObjectMapper json;

    public RunStore(RunWriteMapper runs, ReportWriteMapper reports, ObjectMapper json) {
        this.runs = runs;
        this.reports = reports;
        this.json = json;
    }

    public record Identity(UUID runId, String researchId) { }

    public Identity create(String query, String mode, String source, String tone, Map<String, Object> options) {
        ReportMode.normalize(mode);
        if (!Set.of("web", "local", "hybrid").contains(source)) throw new IllegalArgumentException("不支持的资料来源");
        UUID report = UUID.randomUUID();
        String publicId = UUID.randomUUID().toString();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        reports.create(report, publicId, now);
        reports.update(report, query, "", "[]", "running", now, null);
        UUID run = UUID.randomUUID();
        runs.create(run, report, query, source, tone, redact(json.valueToTree(options == null ? Map.of() : options)).toString());
        return new Identity(run, publicId);
    }

    public long append(UUID id, Map<String, Object> payload) {
        JsonNode row = locked(id);
        if (terminal(row)) throw new IllegalStateException("研究已经结束，不能追加运行事件");
        JsonNode value = redact(json.valueToTree(payload));
        return runs.append(id, value.path("type").asText("event"),
                value.hasNonNull("content") ? value.get("content").asText() : null, value.toString());
    }

    public boolean complete(UUID id, ResearchResult result) {
        JsonNode row = locked(id);
        if (terminal(row)) return false;
        if (result.report() == null || result.report().isBlank() || result.evidence().isEmpty()) {
            throw new IllegalArgumentException("缺少正文或证据，不能保存为正式报告");
        }
        for (Map<String, Object> item : result.evidence()) {
            JsonNode snapshot = json.valueToTree(item);
            int number = snapshot.path("number").asInt();
            if (number < 1 || snapshot.path("content").asText("").isBlank()) {
                throw new IllegalArgumentException("证据快照无效");
            }
            runs.evidence(id, number, snapshot.toString());
        }
        for (Map<String, Object> item : result.sources()) {
            ObjectNode source = json.valueToTree(item);
            String url = source.path("url").asText(source.path("source").asText(""));
            if (url.isEmpty()) continue;
            String title = source.path("title").asText("");
            String content = source.path("raw_content").asText(source.path("content").asText(source.path("body").asText("")));
            String type = source.path("source_type").asText("web");
            source.remove(java.util.List.of("url", "source", "title", "raw_content", "content", "body"));
            runs.source(UUID.randomUUID(), id, url, hash(url), title, content, type, source.toString());
        }
        runs.finish(id, "completed", null, json.valueToTree(result.costs()).toString());
        runs.finishReport(id, UUID.fromString(row.path("report_id").asText()), result.report(), "completed");
        return true;
    }

    public boolean fail(UUID id, String error) {
        JsonNode row = locked(id);
        if (terminal(row)) return false;
        String message = error == null ? "研究失败" : error.substring(0, Math.min(error.length(), 10000));
        runs.finish(id, "failed", message, row.path("cost_summary").toString());
        runs.finishReport(id, UUID.fromString(row.path("report_id").asText()), null, "failed");
        return true;
    }

    private JsonNode locked(UUID id) {
        String value = runs.locked(id);
        if (value == null) throw new IllegalArgumentException("研究任务不存在");
        try { return json.readTree(value); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Invalid run projection", exception); }
    }

    private static boolean terminal(JsonNode row) {
        return Set.of("completed", "failed", "cancelled").contains(row.path("status").asText());
    }

    private static JsonNode redact(JsonNode value) {
        if (value.isArray()) {
            for (JsonNode item : value) redact(item);
        } else if (value instanceof ObjectNode object) {
            var names = new java.util.ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String normalized = name.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
                if (Set.of("authorization", "api_key", "apikey", "token", "secret", "password", "connection_token")
                        .contains(normalized) || normalized.endsWith("_api_key") || normalized.endsWith("_token")
                        || normalized.endsWith("_secret") || normalized.endsWith("_password")) {
                    object.put(name, "[REDACTED]");
                } else redact(object.get(name));
            }
        }
        return value;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
