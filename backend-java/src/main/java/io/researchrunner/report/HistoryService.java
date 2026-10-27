package io.researchrunner.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.infrastructure.HistoryMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class HistoryService {
    private final HistoryMapper mapper;
    private final ObjectMapper json;

    public HistoryService(HistoryMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public List<JsonNode> reports(List<String> ids, int limit, OffsetDateTime cursor, boolean incomplete) {
        return mapper.reports(ids, limit, cursor, incomplete).stream().map(this::parse).toList();
    }

    public JsonNode report(String id) {
        String result = mapper.report(id);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到报告");
        }
        return parse(result);
    }

    public List<JsonNode> documents() {
        return mapper.documents().stream().map(this::parse).toList();
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored history projection is invalid", exception);
        }
    }
}
