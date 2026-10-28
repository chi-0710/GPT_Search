package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** A6-04: MCP stdio allowlist and transport validation. */
class McpConfigValidationTest {
    private final MockEnvironment env = new MockEnvironment()
            .withProperty("JAVA_MCP_ALLOWED_COMMANDS", "node");

    @Test
    void allowsConfiguredStdioCommand() {
        var configs = McpResearch.parse(List.of(Map.of(
                "name", "srv", "command", "node", "args", List.of("server.js"))), env);
        assertEquals(1, configs.size());
        assertEquals("stdio", configs.getFirst().transport());
    }

    @Test
    void rejectsPythonAndShellCommands() {
        for (String command : List.of("python", "python3", "uvx", "bash", "powershell", "cmd")) {
            assertThrows(IllegalArgumentException.class, () -> McpResearch.parse(
                    List.of(Map.of("name", "srv", "command", command)), env), command);
        }
    }

    @Test
    void acceptsWebSocketAndHttpTransport() {
        var websocket = McpResearch.parse(List.of(Map.of(
                "name", "srv", "connection_url", "wss://example.org/mcp")), env);
        assertEquals("websocket", websocket.getFirst().transport());
        var http = McpResearch.parse(List.of(Map.of(
                "name", "srv", "connection_url", "https://example.org/mcp")), env);
        assertEquals("streamable_http", http.getFirst().transport());
        assertThrows(IllegalArgumentException.class, () -> McpResearch.parse(
                List.of(Map.of("name", "srv", "connection_url", "ftp://example.org/mcp")), env));
    }

    @Test
    void rejectsDuplicateOrInvalidNames() {
        assertThrows(IllegalArgumentException.class, () -> McpResearch.parse(List.of(
                Map.of("name", "same", "connection_url", "https://example.org/mcp"),
                Map.of("name", "same", "connection_url", "https://example.org/mcp")), env));
        assertThrows(IllegalArgumentException.class, () -> McpResearch.parse(
                List.of(Map.of("name", "bad name", "connection_url", "https://example.org/mcp")), env));
    }
}
