package io.researchrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A4-11 dry-run inventory plus the extended read-only summary of legacy file storage. */
class LegacyImportCliTest {
    @TempDir
    Path root;

    @Test
    void scansReportsOutputsAndDocuments() throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/reports.json"), "{\"r1\":{\"question\":\"q\"}}", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("outputs"));
        Files.writeString(root.resolve("outputs/r1.md"), "# report", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("my-docs"));
        Files.writeString(root.resolve("my-docs/a.txt"), "doc", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("my-docs/b.txt"), "doc", StandardCharsets.UTF_8);

        var summary = LegacyImportCli.scan(root);
        assertEquals(1, summary.get("reports"));
        assertEquals(1, summary.get("outputs"));
        assertEquals(2, summary.get("documents"));
        assertEquals(true, summary.get("dry_run"));
    }

    @Test
    void dryRunSucceedsWithoutTouchingTheDatabase() {
        assertEquals(0, LegacyImportCli.run(new String[]{"--root", root.toString(), "--dry-run"}));
    }

    @Test
    void missingDirectoriesCountAsZero() throws Exception {
        var summary = LegacyImportCli.scan(root);
        assertEquals(0, summary.get("reports"));
        assertEquals(0, summary.get("outputs"));
        assertEquals(0, summary.get("referenced_outputs"));
        assertEquals(0, summary.get("output_files"));
        assertTrue(summary.get("root").toString().endsWith(root.getFileName().toString()));
    }

    @Test
    void countsReferencedOutputsAndNormalizesOutputPaths() throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/reports.json"), """
                {"r1":{"orderedData":[{"type":"path","output":{"md":"/outputs/r1.md"}}]}}
                """, StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("outputs"));
        Files.writeString(root.resolve("outputs/r1.md"), "# report", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("outputs/orphan.md"), "# orphan", StandardCharsets.UTF_8);

        var summary = LegacyImportCli.scan(root);
        assertEquals(1, summary.get("reports"));
        assertEquals(1, summary.get("referenced_outputs"));
        assertEquals(2, summary.get("output_files"));
        assertEquals(2, summary.get("outputs"));
        assertEquals(true, summary.get("dry_run"));
    }

    @Test
    void rejectsUnknownOrIncompleteArguments() {
        assertEquals(2, LegacyImportCli.run(new String[]{"--unknown"}));
        assertEquals(2, LegacyImportCli.run(new String[]{"--root"}));
    }

    @Test
    void nonexistentOrNonDirectoryRootIsAnEmptyDryRun() throws Exception {
        Path missing = root.resolve("does-not-exist");
        var summary = LegacyImportCli.scan(missing);
        assertEquals(0, summary.get("reports"));
        assertEquals(0, summary.get("outputs"));
        assertEquals(0, summary.get("output_files"));
        assertEquals(0, summary.get("documents"));
        assertEquals(true, summary.get("dry_run"));
        assertEquals(0, LegacyImportCli.run(new String[]{"--root", missing.toString(), "--dry-run"}));

        Path file = Files.writeString(root.resolve("not-a-directory"), "x", StandardCharsets.UTF_8);
        var fileSummary = LegacyImportCli.scan(file);
        assertEquals(0, fileSummary.get("reports"));
        assertEquals(0, fileSummary.get("documents"));
        assertEquals(0, LegacyImportCli.run(new String[]{"--root", file.toString(), "--dry-run"}));
    }

    @Test
    void relativeRootResolvesAgainstTheWorkingDirectory() throws Exception {
        var summary = LegacyImportCli.scan(Path.of("."));
        assertEquals(Path.of(".").toAbsolutePath().normalize().toString(), summary.get("root"));
        assertEquals(true, summary.get("dry_run"));
    }
}
