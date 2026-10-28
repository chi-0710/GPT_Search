package io.researchrunner.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A5-04: non-text MCP content handling. */
class McpContentTest {
    @Test
    void textEmbeddedTextAndResourceLinksBecomeEvidenceButImagesDoNot() {
        var result = McpSchema.CallToolResult.builder()
                .content(List.of(
                        new McpSchema.TextContent("纯文本"),
                        new McpSchema.EmbeddedResource(null,
                                new McpSchema.TextResourceContents("file:///doc.txt", "text/plain", "内嵌文本")),
                        new McpSchema.ResourceLink("资源", "标题", "https://example.org/r", "说明", "text/html", 10L, null, null),
                        new McpSchema.ImageContent(null, "image/png", "AAAA")))
                .build();
        List<String> text = McpResearch.contentText(result);
        assertTrue(text.contains("纯文本"));
        assertTrue(text.contains("内嵌文本"));
        assertTrue(text.stream().anyMatch(line -> line.contains("https://example.org/r")));
        assertTrue(text.stream().anyMatch(line -> line.contains("图片内容已省略")));
        assertEquals(4, text.size());
    }

    @Test
    void emptyContentIsEmpty() {
        assertTrue(McpResearch.contentText(McpSchema.CallToolResult.builder().content(List.of()).build()).isEmpty());
    }
}
