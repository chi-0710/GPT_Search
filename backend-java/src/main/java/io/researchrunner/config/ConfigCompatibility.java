package io.researchrunner.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * A2-09: Java must not silently ignore Python configuration keys.
 *
 * <p>Keys whose behavior is not migrated are reported once at startup with a warning, so an
 * operator can see that setting them has no effect. Keys that would change the research result
 * (SCRAPER handled in ResearchExecutionService, image generation, curation) are rejected there
 * or by the request validator; this class covers the informational remainder.
 */
@Component
public class ConfigCompatibility {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigCompatibility.class);

    public static final List<String> NOT_MIGRATED = List.of(
            "CURATE_SOURCES", "MCP_SERVERS", "MCP_STRATEGY", "MCP_ALLOWED_ROOT_PATHS",
            "MCP_AUTO_TOOL_SELECTION", "MAX_ITERATIONS",
            "IMAGE_GENERATION_STYLE", "PROMPT_FAMILY", "REPORT_FORMAT", "MEMORY_BACKEND",
            "REASONING_EFFORT", "MAX_SCRAPER_WORKERS", "SCRAPER_RATE_LIMIT_DELAY",
            "COMPRESSION_THRESHOLD", "USER_AGENT", "LANGUAGE");

    @EventListener(ApplicationReadyEvent.class)
    public void reportUnmigratedKeys(ApplicationReadyEvent event) {
        reportUnmigratedKeys(event.getApplicationContext().getEnvironment());
    }

    /** Returns the ignored keys that are explicitly set, for tests and diagnostics. */
    public List<String> reportUnmigratedKeys(Environment environment) {
        List<String> ignored = NOT_MIGRATED.stream()
                .filter(key -> {
                    String value = environment.getProperty(key);
                    return value != null && !value.isBlank();
                })
                .toList();
        for (String key : ignored) {
            LOG.warn("配置 {} 在 Java 后端尚未迁移，当前设置会被忽略", key);
        }
        return ignored;
    }
}
