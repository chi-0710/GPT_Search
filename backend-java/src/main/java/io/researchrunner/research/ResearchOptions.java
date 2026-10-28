package io.researchrunner.research;

import java.util.*;
import org.springframework.core.env.Environment;

/** Validated immutable task options, shared by every transport. */
public record ResearchOptions(ResearchNodes.Settings nodes, Integer planLimit, Integer factLimit,
        boolean humanReview, boolean pdf, boolean docx) {
    private static final List<String> DEFAULT_GUIDELINES = List.of("报告必须使用 APA 格式",
            "每个子章节都必须通过超链接提供支持性来源；如果没有可用来源，请删除该子章节，或将其改写为上一章节的一部分",
            "报告必须使用简体中文撰写");

    public static ResearchOptions parse(Map<String,Object> request, Environment env, String query, String tone) {
        Map<String,Object> options = new LinkedHashMap<>();
        if (request.containsKey("task_options")) {
            if (!(request.get("task_options") instanceof Map<?,?> nested)) throw invalid("task_options");
            for (var entry : nested.entrySet()) {
                String key=entry.getKey().toString();
                if(!Set.of("max_sections","max_plan_revisions","max_draft_revisions","max_fact_check_revisions",
                        "include_human_feedback","follow_guidelines","guidelines").contains(key))
                    throw new IllegalArgumentException("尚不支持的研究任务配置："+key);
                options.put(key, entry.getValue());
            }
        }
        for (String key : List.of("max_sections", "max_plan_revisions", "max_draft_revisions",
                "max_fact_check_revisions", "include_human_feedback", "follow_guidelines", "guidelines")) {
            if (request.containsKey(key)) options.put(key, request.get(key));
        }
        List<String> guidelines = DEFAULT_GUIDELINES;
        if (options.containsKey("guidelines")) {
            if (!(options.get("guidelines") instanceof List<?> values)
                    || values.stream().anyMatch(v -> !(v instanceof String))) throw invalid("guidelines");
            guidelines = values.stream().map(String.class::cast).toList();
        }
        var settings = new ResearchNodes.Settings(query, tone, guidelines, bool(options,"follow_guidelines",false),
                integer(options,"max_sections",3,1), env.getProperty("JAVA_SECTION_CONCURRENCY",Integer.class,3),
                limit(options,"max_draft_revisions"), env.getProperty("JAVA_LOCAL_CONTEXT_BUDGET",Integer.class,10000),
                env.getProperty("JAVA_WEB_CONTEXT_BUDGET",Integer.class,10000));
        return new ResearchOptions(settings,limit(options,"max_plan_revisions"),limit(options,"max_fact_check_revisions"),
                bool(options,"include_human_feedback",false),bool(request,"export_pdf",true),bool(request,"export_docx",true));
    }
    private static Integer limit(Map<String,Object> values,String key) {
        return values.containsKey(key) && values.get(key)==null ? null : integer(values,key,3,0);
    }
    private static int integer(Map<String,Object> values,String key,int fallback,int minimum) {
        Object value=values.getOrDefault(key,fallback);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue()!=number.intValue() || number.intValue()<minimum) throw invalid(key);
        return number.intValue();
    }
    private static boolean bool(Map<String,Object> values,String key,boolean fallback) {
        Object value=values.getOrDefault(key,fallback);
        if (!(value instanceof Boolean flag)) throw invalid(key);
        return flag;
    }
    private static IllegalArgumentException invalid(String key) {return new IllegalArgumentException("研究参数无效："+key);}
}
