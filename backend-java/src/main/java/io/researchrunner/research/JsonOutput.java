package io.researchrunner.research;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.regex.Pattern;

/**
 * A4-04: tolerant parser for model-produced JSON.
 *
 * <p>Mirrors Python's json_repair/json5 usage for the common failure modes: fenced blocks,
 * prose around the object, trailing commas, single quotes, comments and Python literals
 * ({@code True/False/None}). The object must still be an object; invalid output fails the stage.
 */
final class JsonOutput {
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private JsonOutput() { }

    static JsonNode parseObject(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("模型返回空内容");
        String text = raw.strip();
        var fence = FENCE.matcher(text);
        if (fence.find()) text = fence.group(1).strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("模型必须返回 JSON 对象");
        text = text.substring(start, end + 1);
        text = text.replace("True", "true").replace("False", "false").replace("None", "null");
        text = TRAILING_COMMA.matcher(text).replaceAll("$1");
        try {
            JsonNode value = LENIENT.readTree(text);
            if (value == null || !value.isObject()) throw new IllegalStateException("模型必须返回 JSON 对象");
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException first) {
            throw new IllegalStateException("模型返回的 JSON 无法解析", first);
        }
    }
}
