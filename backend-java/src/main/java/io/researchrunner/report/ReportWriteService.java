package io.researchrunner.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.infrastructure.ReportWriteMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled", havingValue="true")
@Transactional
public class ReportWriteService {
    private static final Set<String> STATUSES = Set.of("queued", "running", "completed", "failed");
    private final ReportWriteMapper mapper;
    private final ObjectMapper json;

    public ReportWriteService(ReportWriteMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public String upsert(JsonNode input) {
        requireObject(input);
        String id = textOr(input.get("id"), UUID.randomUUID().toString());
        if (id.length() > 160) throw conflict("Report id is too long");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime incoming = input.path("timestamp").isIntegralNumber()
                ? Instant.ofEpochMilli(input.path("timestamp").longValue()).atOffset(ZoneOffset.UTC) : now;
        mapper.lockIdentity(id);
        mapper.create(UUID.randomUUID(), id, incoming);
        JsonNode current = locked(id);
        write(current, input, incoming, false);
        replaceChat(current, input);
        return id;
    }

    public void patch(String id, JsonNode input) {
        requireObject(input);
        mapper.lockIdentity(id);
        JsonNode current = locked(id);
        write(current, input, OffsetDateTime.now(ZoneOffset.UTC), true);
        replaceChat(current, input);
    }

    private void write(JsonNode row, JsonNode input, OffsetDateTime time, boolean patch) {
        String question = input.hasNonNull("question")
                ? (patch ? text(input.get("question")) : textOr(input.get("question"), row.path("question").asText("")))
                : row.path("question").asText("");
        String answer = input.hasNonNull("answer") ? text(input.get("answer")) : row.path("answer").asText("");
        JsonNode ordered = row.path("ordered_data");
        if (input.hasNonNull("orderedData")) {
            if (input.get("orderedData").isArray()) ordered = input.get("orderedData");
            else if (!patch) ordered = json.createArrayNode();
        }
        String requested = input.path("status").asText("");
        String status = row.path("status").asText("queued");
        if (patch) {
            if (STATUSES.contains(requested)) status = requested;
            else if (!answer.isEmpty()) status = "completed";
        } else {
            status = !requested.isEmpty() ? requested : !answer.isEmpty() ? "completed" : status;
            if (!STATUSES.contains(status)) throw conflict("Invalid report status");
            var previous = OffsetDateTime.parse(row.path("updated_at").asText());
            if (previous.isAfter(time)) time = previous;
        }
        OffsetDateTime completed = row.hasNonNull("completed_at")
                ? OffsetDateTime.parse(row.get("completed_at").asText()) : null;
        if (status.equals("completed") && completed == null) completed = time;
        mapper.update(UUID.fromString(row.path("id").asText()), question, answer, ordered.toString(), status, time, completed);
    }

    private void replaceChat(JsonNode row, JsonNode input) {
        if (!input.path("chatMessages").isArray()) return;
        UUID report = UUID.fromString(row.path("id").asText());
        mapper.clearChat(report);
        long sequence = 1;
        for (JsonNode message : input.get("chatMessages")) insertMessage(report, UUID.randomUUID(), sequence++, message);
    }

    public void addMessage(String id, JsonNode message) {
        requireObject(message);
        mapper.lockIdentity(id);
        UUID report = UUID.fromString(locked(id).path("id").asText());
        String identifier = textOr(message.get("id"), textOr(message.get("messageId"), UUID.randomUUID().toString()));
        UUID messageId;
        try { messageId = UUID.fromString(identifier); }
        catch (IllegalArgumentException exception) { throw conflict("Message id must be a valid UUID"); }
        String owner = mapper.messageOwner(messageId);
        if (owner != null) {
            if (!owner.equals(report.toString())) throw conflict("Message id already belongs to another report");
            return;
        }
        insertMessage(report, messageId, mapper.nextSequence(report), message);
        mapper.touch(report, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void insertMessage(UUID report, UUID id, long sequence, JsonNode message) {
        requireObject(message);
        String role = textOr(message.get("role"), "user");
        if (!Set.of("user", "assistant", "system").contains(role)) throw conflict("Invalid message role");
        mapper.insertChat(id, report, sequence, role, textOr(message.get("content"), ""),
                message.hasNonNull("metadata") ? message.get("metadata").toString() : null,
                message.path("timestamp").isIntegralNumber() ? message.get("timestamp").longValue() : null);
    }

    public void delete(String id) {
        if (mapper.delete(id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到报告");
    }

    public int clear() {
        int count = mapper.clearReports();
        mapper.clearRuns();
        mapper.clearArtifacts();
        return count;
    }

    private JsonNode locked(String id) {
        String value = mapper.lockedReport(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到报告");
        try { return json.readTree(value); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Invalid stored report", exception); }
    }

    private static String text(JsonNode value) { return value.isValueNode() ? value.asText() : value.toString(); }
    private static String textOr(JsonNode value, String fallback) {
        return value == null || value.isNull() || value.asText().isEmpty() ? fallback : text(value);
    }
    private static void requireObject(JsonNode value) {
        if (value == null || !value.isObject()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请求参数无效");
    }
    private static ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }
}
