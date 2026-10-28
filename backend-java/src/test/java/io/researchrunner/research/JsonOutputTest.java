package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** A4-04: model output repair for fenced/prose/loose JSON. */
class JsonOutputTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesFencedJsonWithProse() {
        var value = JsonOutput.parseObject(json, """
                Here is the plan:
                ```json
                {"title": "中文标题", "sections": ["章节一", "章节二"]}
                ```
                done
                """);
        assertEquals("中文标题", value.path("title").asText());
        assertEquals(2, value.path("sections").size());
    }

    @Test
    void acceptsTrailingCommasSingleQuotesCommentsAndPythonLiterals() {
        var value = JsonOutput.parseObject(json, """
                {
                  // plan
                  'accepted': True,
                  'feedback': '需要补充证据',
                }
                """);
        assertEquals(true, value.path("accepted").asBoolean());
        assertEquals("需要补充证据", value.path("feedback").asText());
    }

    @Test
    void pythonNoneBecomesNull() {
        var value = JsonOutput.parseObject(json, "{\"feedback\": None}");
        assertEquals(true, value.path("feedback").isNull());
    }

    @Test
    void rejectsEmptyOrNonObject() {
        assertThrows(IllegalStateException.class, () -> JsonOutput.parseObject(json, ""));
        assertThrows(IllegalStateException.class, () -> JsonOutput.parseObject(json, "not json"));
        assertThrows(IllegalStateException.class, () -> JsonOutput.parseObject(json, "[1,2,3]"));
    }
}
