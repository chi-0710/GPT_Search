package io.researchrunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * A4-11: legacy storage import CLI ({@code data/reports.json}, {@code outputs/}, {@code my-docs/}).
 *
 * <p>{@code --dry-run} keeps the original read-only inventory and never touches a database.
 * A write run boots the same write-enabled Spring context as {@link MaintenanceCli} and prints
 * the JSON summary produced by {@link LegacyImportService}; document import failures keep the
 * exit code non-zero while still printing the summary.
 */
public final class LegacyImportCli {
    private LegacyImportCli() { }

    /** Read-only inventory; keys kept compatible with the original dry-run output. */
    public static Map<String,Object> scan(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        JsonNode reports = LegacyImportService.loadReports(base.resolve("data").resolve("reports.json"));
        int outputFiles = LegacyImportService.countFiles(base.resolve("outputs"));
        Map<String,Object> summary = new LinkedHashMap<>();
        summary.put("reports", reports.size());
        summary.put("outputs", outputFiles);
        summary.put("documents", LegacyImportService.countFiles(base.resolve("my-docs")));
        summary.put("referenced_outputs", LegacyImportService.referencedOutputs(reports).size());
        summary.put("output_files", outputFiles);
        summary.put("root", base.toString());
        summary.put("dry_run", true);
        return summary;
    }

    public static int run(String[] args) {
        Path root = Path.of(".");
        boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> {
                    if (++i >= args.length) { System.err.println("--root 缺少路径"); return 2; }
                    root = Path.of(args[i]);
                }
                case "--dry-run" -> dryRun = true;
                default -> { System.err.println("未知参数：" + args[i]); return 2; }
            }
        }
        if (dryRun) {
            try {
                System.out.println(new ObjectMapper().writeValueAsString(scan(root)));
                return 0;
            } catch (Exception error) {
                System.err.println("legacy 盘点失败：" + describe(error));
                return 1;
            }
        }
        try {
            return importLegacy(root);
        } catch (Exception error) {
            System.err.println("legacy 导入失败：" + describe(error));
            return 1;
        }
    }

    /** Keeps the class name visible without ever echoing a stack trace or configuration value. */
    private static String describe(Exception error) {
        return error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : "（" + error.getMessage() + "）");
    }

    private static int importLegacy(Path root) throws IOException {
        Map<String,Object> summary;
        try (var context = new SpringApplicationBuilder(ResearchRunnerApplication.class)
                .web(WebApplicationType.NONE)
                .run("--researchrunner.writes-enabled=true", "--spring.datasource.hikari.read-only=false",
                        "--JAVA_RECOVER_INTERRUPTED_TASKS=false")) {
            summary = context.getBean(LegacyImportService.class).importAll(root);
            System.out.println(context.getBean(ObjectMapper.class).writeValueAsString(summary));
        }
        Object failures = summary.get("failed_documents");
        return failures instanceof List<?> list && !list.isEmpty() ? 1 : 0;
    }
}
