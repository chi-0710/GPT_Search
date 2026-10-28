package io.researchrunner.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A4-08: recursive splitter behavior on paragraphs, words and Unicode. */
class TextSplitterTest {
    @Test
    void prefersParagraphsAndRespectsSize() {
        var splitter = new TextSplitter(12, 2);
        var chunks = splitter.split("第一段内容。\n\n第二段内容比较长一些。\n\n第三段。");
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.codePointCount(0, chunk.length()) <= 14),
                chunks.toString());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("第一段")));
    }

    @Test
    void longUnbrokenTextIsCutIntoBoundedChunks() {
        var splitter = new TextSplitter(4, 1);
        var chunks = splitter.split("甲乙丙丁戊己庚辛");
        assertEquals("甲乙丙丁", chunks.getFirst());
        assertEquals("丁戊己庚", chunks.get(1));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.codePointCount(0, chunk.length()) <= 4));
    }

    @Test
    void unicodeCodePointsAreNeverSplit() {
        var splitter = new TextSplitter(3, 1);
        var chunks = splitter.split("😀😁😂🤣😃");
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.contains("\uFFFD")));
        assertTrue(chunks.stream().allMatch(chunk -> !chunk.isEmpty()));
    }

    @Test
    void blankInputReturnsNoChunks() {
        assertTrue(new TextSplitter(10, 2).split("   ").isEmpty());
        assertFalse(new TextSplitter(10, 2).split("内容").isEmpty());
    }

    @Test
    void pageMetadataIsPreservedByParser() throws Exception {
        var parser = new DocumentParser();
        List<DocumentParser.Part> parts = parser.split(List.of(new DocumentParser.Part("第一段。\n\n第二段。", 7)), 50, 5);
        assertEquals(1, parts.size());
        assertEquals(7, parts.getFirst().page());
    }
}
