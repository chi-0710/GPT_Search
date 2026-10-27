package io.researchrunner.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.infrastructure.ResourceMapper;
import io.researchrunner.infrastructure.StoredFile;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class ResourceService {
    private final ResourceMapper mapper;
    private final HistoryService history;
    private final ObjectMapper json;

    public ResourceService(ResourceMapper mapper, HistoryService history, ObjectMapper json) {
        this.mapper = mapper;
        this.history = history;
        this.json = json;
    }

    public JsonNode run(String identifier) {
        UUID id = parseUuid(identifier);
        String result = id == null ? null : mapper.runById(id);
        if (result == null) {
            result = mapper.runByReport(identifier);
        }
        if (result == null) {
            throw missing("未找到研究任务");
        }
        return parse(result);
    }

    public List<JsonNode> events(String identifier, long after, int limit) {
        return mapper.events(runId(run(identifier)), after, limit).stream().map(this::parse).toList();
    }

    public List<JsonNode> evidence(String identifier) {
        return mapper.evidence(runId(run(identifier))).stream().map(this::parse).toList();
    }

    public JsonNode evidenceItem(String identifier, int number) {
        return evidence(identifier).stream().filter(item -> item.path("number").asInt() == number)
                .findFirst().orElseThrow(() -> missing("未找到证据"));
    }

    public Map<String, Object> log(String identifier) {
        JsonNode run = run(identifier);
        UUID id = runId(run);
        List<JsonNode> sources = mapper.sources(id).stream().map(this::parse).toList();
        List<Map<String, Object>> events = new ArrayList<>();
        long after = 0;
        while (true) {
            List<JsonNode> batch = mapper.events(id, after, 2000).stream().map(this::parse).toList();
            for (JsonNode event : batch) {
                events.add(Map.of("timestamp", event.path("createdAt"), "type", "event", "data", event));
            }
            if (batch.size() < 2000) break;
            after = batch.getLast().path("id").asLong();
        }
        String answer = "";
        if (!run.path("research_id").asText().isEmpty()) {
            try {
                answer = history.report(run.path("research_id").asText()).path("answer").asText("");
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode() != HttpStatus.NOT_FOUND) throw exception;
            }
        }
        return Map.of("timestamp", run.path("started_at"), "events", events, "content", Map.of(
                "query", run.path("query"), "sources", sources.stream().map(s -> s.path("url")).toList(),
                "context", sources, "report", answer, "costs", run.path("costs")));
    }

    public StoredFile artifact(String id) {
        UUID uuid = parseUuid(id);
        return required(uuid == null ? null : mapper.artifact(uuid), "未找到报告附件");
    }

    public StoredFile document(String id) {
        UUID uuid = parseUuid(id);
        return required(uuid == null ? null : mapper.document(uuid), "原文件已删除或不存在");
    }

    public StoredFile reportDocument(String id) {
        return required(mapper.reportDocument(id), "未找到报告");
    }

    public StoredFile legacyArtifact(String path) {
        // Match Python PurePosixPath normalization, without reading the filesystem.
        String normalized = Arrays.stream(path.replace('\\', '/').strip().split("/+"))
                .filter(part -> !part.isEmpty() && !part.equals("."))
                .reduce((left, right) -> left + "/" + right).orElse(".");
        return required(mapper.legacyArtifact(normalized,
                normalized.startsWith("outputs/") ? normalized : "outputs/" + normalized), "未找到报告附件");
    }

    private UUID runId(JsonNode run) { return UUID.fromString(run.path("id").asText()); }

    private UUID parseUuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private StoredFile required(StoredFile value, String message) {
        if (value == null) throw missing(message);
        return value;
    }

    private ResponseStatusException missing(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored resource projection is invalid", exception);
        }
    }
}
