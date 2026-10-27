package io.researchrunner.research;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ReportModeTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"multi_agents", "research_report", "detailed_report", "deep",
            "resource_report", "outline_report", "custom_report", "subtopic_report"})
    void acceptsOnlyKnownCompatibilityAliases(String mode) {
        assertEquals("multi_agents", ReportMode.normalize(mode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ag2", "unknown", " multi_agents", "MULTI_AGENTS"})
    void rejectsUnknownModes(String mode) {
        assertThrows(IllegalArgumentException.class, () -> ReportMode.normalize(mode));
    }
}
