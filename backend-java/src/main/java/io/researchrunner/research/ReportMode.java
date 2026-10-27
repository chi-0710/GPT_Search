package io.researchrunner.research;

import java.util.Set;

/** Public mode compatibility; never rewrites stored historical metadata. */
public final class ReportMode {
    private static final Set<String> LEGACY = Set.of("research_report", "detailed_report", "deep",
            "resource_report", "outline_report", "custom_report", "subtopic_report");

    private ReportMode() { }

    public static String normalize(String value) {
        if (value == null || value.equals("multi_agents") || LEGACY.contains(value)) {
            return "multi_agents";
        }
        throw new IllegalArgumentException("不支持的报告模式：" + value);
    }
}
