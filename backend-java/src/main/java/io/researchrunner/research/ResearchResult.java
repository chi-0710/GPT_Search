package io.researchrunner.research;

import java.util.List;
import java.util.Map;

/** Shared result contract for the future HTTP, WebSocket and CLI execution adapters. */
public record ResearchResult(String report, List<Map<String, Object>> sources,
        Map<String, Object> costs, List<Map<String, Object>> evidence, List<String> warnings) { }
