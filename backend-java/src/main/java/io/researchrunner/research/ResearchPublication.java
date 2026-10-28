package io.researchrunner.research;

import io.researchrunner.report.ReportExports;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All publication writes either commit together or roll back together. */
@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class ResearchPublication {
    private final RunStore runs;
    private final ReportExports exports;
    private final io.researchrunner.report.ArtifactStore artifacts;
    public ResearchPublication(RunStore runs,ReportExports exports) {this(runs,exports,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public ResearchPublication(RunStore runs,ReportExports exports,io.researchrunner.report.ArtifactStore artifacts) {this.runs=runs;this.exports=exports;this.artifacts=artifacts;}

    @Transactional(rollbackFor=Exception.class)
    public Map<String,String> publish(RunStore.Identity run,ResearchResult result,boolean pdf,boolean docx) throws Exception {
        return publish(run,result,pdf,docx,java.util.List.of());
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,String> publish(RunStore.Identity run,ResearchResult result,boolean pdf,boolean docx,
            java.util.List<io.researchrunner.report.ReportFigure> figures) throws Exception {
        // Acquire the same run row lock used by terminal transitions before creating attachments.
        runs.append(run.runId(),Map.of("type","logs","content","publishing","output","正在保存研究报告"));
        for(var figure:figures) {
            if(artifacts==null)throw new IllegalStateException("图片附件服务不可用");
            artifacts.saveWithId(figure.id(),figure.png(),figure.filename(),"image","image/png",run.researchId(),run.runId(),
                    "outputs/"+figure.filename(),Map.of("generator","java-flowchart"));
        }
        var saved=exports.saveWithWarnings(run.researchId(),run.runId(),result.report(),pdf,docx);
        for(String warning:saved.warnings()) runs.append(run.runId(),Map.of("type","logs","content","warning","output",warning));
        var paths=saved.paths();
        for(String warning:result.warnings()) runs.append(run.runId(),Map.of("type","logs","content","warning","output",warning));
        runs.append(run.runId(),Map.of("type","report_complete","output",result.report()));
        runs.append(run.runId(),Map.of("type","path","output",paths));
        if(!runs.complete(run.runId(),result)) throw new IllegalStateException("研究已结束，不能发布");
        return paths;
    }
}
