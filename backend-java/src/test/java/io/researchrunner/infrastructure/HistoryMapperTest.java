package io.researchrunner.infrastructure;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryMapperTest {
    @Test
    void dynamicQueriesCompileAndKeepUserValuesParameterized() {
        var configuration = new Configuration();
        configuration.addMapper(HistoryMapper.class);
        configuration.addMapper(ResourceMapper.class);
        var statement = configuration.getMappedStatement(HistoryMapper.class.getName() + ".reports");
        var arguments = new HashMap<String, Object>();
        arguments.put("ids", null);
        arguments.put("cursor", null);
        arguments.put("limit", 50);
        arguments.put("includeIncomplete", false);
        var defaults = statement.getBoundSql(arguments);
        assertTrue(defaults.getSql().contains("r.status = 'completed'"));
        arguments.put("ids", List.of("' OR 1=1 --"));
        arguments.put("cursor", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        var selected = statement.getBoundSql(arguments);
        assertFalse(selected.getSql().contains("OR 1=1"));
        assertFalse(selected.getSql().contains("r.status = 'completed'"));
        assertTrue(selected.getSql().contains("r.updated_at < ?"));
        assertEquals(3, selected.getParameterMappings().size());
    }
}
