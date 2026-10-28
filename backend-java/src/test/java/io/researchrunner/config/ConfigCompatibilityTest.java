package io.researchrunner.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConfigCompatibilityTest {
    private final ConfigCompatibility compatibility = new ConfigCompatibility();

    @Test
    void reportsOnlyExplicitlySetUnmigratedKeys() {
        var environment = new MockEnvironment()
                .withProperty("CURATE_SOURCES", "true")
                .withProperty("IMAGE_GENERATION_STYLE", "dark")
                .withProperty("FAST_LLM", "deepseek:chat");

        List<String> ignored = compatibility.reportUnmigratedKeys(environment);

        assertEquals(List.of("CURATE_SOURCES", "IMAGE_GENERATION_STYLE"), ignored);
        assertTrue(ignored.stream().noneMatch("FAST_LLM"::equals));
    }

    @Test
    void emptyEnvironmentReportsNothing() {
        assertEquals(List.of(), compatibility.reportUnmigratedKeys(new MockEnvironment()));
    }
}
