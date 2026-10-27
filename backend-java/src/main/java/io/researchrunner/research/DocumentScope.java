package io.researchrunner.research;

import java.util.*;

/** Resolve exactly once before run creation; unavailable selections never broaden the scope. */
public final class DocumentScope {
    private DocumentScope() { }

    public static List<String> freeze(List<Map<String, Object>> documents, String profile,
            String scope, List<String> ids, String source) {
        if (!Set.of("web", "local", "hybrid").contains(source)) throw new IllegalArgumentException("不支持的资料来源");
        if (source.equals("web")) return List.of();
        if (!Set.of("all", "selected").contains(scope)) throw new IllegalArgumentException("document_scope 必须为 all 或 selected");
        Set<String> ready = new TreeSet<>();
        for (var document : documents) {
            if ("ready".equals(document.get("status")) && profile.equals(document.get("embedding_profile_id"))) {
                ready.add(document.get("id").toString());
            }
        }
        List<String> result;
        if (scope.equals("selected")) {
            if (ids.isEmpty()) throw new IllegalArgumentException("请至少选择一份可检索文件");
            if (!ready.containsAll(ids)) throw new IllegalArgumentException("所选文件不存在、尚未完成索引或向量模型不匹配，请刷新资料库");
            result = List.copyOf(new LinkedHashSet<>(ids));
        } else result = List.copyOf(ready);
        if (source.equals("local") && result.isEmpty()) throw new IllegalArgumentException("没有可检索的文件，请先上传文件并完成索引");
        return result;
    }
}
