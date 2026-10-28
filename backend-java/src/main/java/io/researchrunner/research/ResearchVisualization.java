package io.researchrunner.research;

import java.util.*;

/** Migrates the existing Mermaid visualization stage without executing model-produced code. */
public final class ResearchVisualization implements ResearchNodes.Visualizer {
    private static final PromptCatalog PROMPTS=PromptCatalog.shared();
    private final ResearchNodes.Model model;
    private final EvidenceRegistry evidence;
    private final ResearchNodes.Progress progress;
    private final UUID run;
    private final String origin;
    private final java.util.function.Consumer<io.researchrunner.report.ReportFigure> figures;
    public ResearchVisualization(ResearchNodes.Model model,EvidenceRegistry evidence,ResearchNodes.Progress progress) {
        this(model,evidence,progress,null,null);
    }
    public ResearchVisualization(ResearchNodes.Model model,EvidenceRegistry evidence,ResearchNodes.Progress progress,UUID run,
            java.util.function.Consumer<io.researchrunner.report.ReportFigure> figures) {
        this(model,evidence,progress,run,figures,"");
    }
    public ResearchVisualization(ResearchNodes.Model model,EvidenceRegistry evidence,ResearchNodes.Progress progress,UUID run,
            java.util.function.Consumer<io.researchrunner.report.ReportFigure> figures,String origin) {
        this.model=model;this.evidence=evidence;this.progress=progress;
        this.run=run;this.figures=figures;this.origin=origin.replaceAll("/+$","");
    }
    @Override public String render(String markdown) throws Exception {
        progress.emit("visualizing","正在依据研究证据生成可视化图表");
        String response=model.generate("visualizer",
                PROMPTS.instruction("visualizer",Map.of("data",markdown)),
                Map.of("report",markdown,"evidence",evidence.snapshots()));
        if(response==null)throw new IllegalStateException("可视化返回空结果");
        String diagram=response.strip();
        if(diagram.equals("None")) {
            progress.emit("visualization_empty","没有适合可视化的数据");return markdown;
        }
        if(!diagram.startsWith("```mermaid\n") || !diagram.endsWith("```") || diagram.length()>30000)
            throw new IllegalStateException("可视化结果必须为单个 Mermaid 代码块或 None");
        String body=diagram.substring(11,diagram.length()-3).strip();
        if(body.isEmpty() || body.contains("```") || java.util.regex.Pattern.compile("<[A-Za-z/!]").matcher(body).find()
                || body.contains("%%{") || body.contains("://")
                || java.util.regex.Pattern.compile("(?im)^\\s*click\\b").matcher(body).find())
            throw new IllegalStateException("可视化包含不支持的标记或链接");
        progress.emit("visualization_generated","可视化图表已生成");
        if(run!=null) {
            byte[] png=new io.researchrunner.report.FlowchartRenderer().png(diagram);
            UUID id=UUID.nameUUIDFromBytes((run+":figure:1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            figures.accept(new io.researchrunner.report.ReportFigure(id,run+"-figure-1.png",png));
            return markdown+"\n\n## 可视化\n\n![研究流程图]("+origin+"/api/artifacts/"+id+")\n";
        }
        return markdown+"\n\n## 可视化\n\n"+diagram+"\n";
    }
}
