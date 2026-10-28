package io.researchrunner.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** A4-09: page-level PDF fidelity against the shared-library fixtures. */
class DocumentFidelityTest {
    private final DocumentParser parser = new DocumentParser();

    private static Path fixtures() {
        return Path.of("src", "test", "resources", "fixtures", "shared_library");
    }

    @Test
    void textPdfKeepsPageNumbersAndFindsKnownSentences() throws Exception {
        Path root = fixtures();
        Assumptions.assumeTrue(Files.exists(root.resolve("paper_a.pdf")),
                "PDF fidelity fixtures are missing from src/test/resources/fixtures/shared_library");
        var paperA = parser.parse("paper_a.pdf", Files.readAllBytes(root.resolve("paper_a.pdf")));
        assertEquals(2, paperA.size());
        assertEquals(1, paperA.get(0).page());
        assertEquals(2, paperA.get(1).page());
        String pageTwo = paperA.get(1).content();
        assertTrue(pageTwo.contains("ALPHA"), pageTwo);
        assertTrue(pageTwo.contains("17-day"), pageTwo);

        var paperB = parser.parse("paper_b.pdf", Files.readAllBytes(root.resolve("paper_b.pdf")));
        assertTrue(paperB.get(1).content().contains("BETA"));
        assertTrue(paperB.get(1).content().contains("83"));
    }

    @Test
    void chunksKeepPageMetadataForKnownSentence() throws Exception {
        Path root = fixtures();
        Assumptions.assumeTrue(Files.exists(root.resolve("paper_a.pdf")),
                "PDF fidelity fixtures are missing from src/test/resources/fixtures/shared_library");
        var pages = parser.parse("paper_a.pdf", Files.readAllBytes(root.resolve("paper_a.pdf")));
        List<DocumentParser.Part> chunks = parser.split(pages, 24, 4);
        assertTrue(chunks.size() > 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.page() == 1 || chunk.page() == 2));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("ALPHA")));
    }
}
