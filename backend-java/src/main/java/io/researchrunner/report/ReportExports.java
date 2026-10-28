package io.researchrunner.report;

import io.researchrunner.infrastructure.ResourceMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class ReportExports {
    private final ArtifactStore artifacts;
    private final ResourceMapper resources;
    private final String origin;
    public ReportExports(ArtifactStore artifacts,ResourceMapper resources,@Value("${PUBLIC_API_URL:http://localhost:8000}") String origin) {
        this.artifacts=artifacts;this.resources=resources;this.origin=origin.replaceAll("/+$","");
    }

    /** Python degrades: Markdown is always saved; a failed PDF/DOCX is skipped with a warning. */
    public record Saved(Map<String,String> paths,List<String> warnings) { }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,String> save(String report,UUID run,String markdown,boolean pdf,boolean docx) throws Exception {
        return saveWithWarnings(report,run,markdown,pdf,docx).paths();
    }

    @Transactional(rollbackFor=Exception.class)
    public Saved saveWithWarnings(String report,UUID run,String markdown,boolean pdf,boolean docx) throws Exception {
        String exported=markdown.replaceAll("\\]\\((/api/research-runs/[0-9a-fA-F-]{36}/evidence/\\d+/view)\\)",java.util.regex.Matcher.quoteReplacement("]("+origin)+"$1)");
        // Only our configured public origin may resolve to local image bytes; never fetch remote URLs.
        exported=exported.replaceAll("!\\[([^\\]]*)\\]\\("+java.util.regex.Pattern.quote(origin)+"(/api/artifacts/[0-9a-fA-F-]{36})\\)","![$1]($2)");
        var renderer=new ReportRenderer(id->{var file=resources.artifact(id);return file==null?null:file.content();});
        String portable=exported.replaceAll("\\]\\((/api/artifacts/[0-9a-fA-F-]{36})\\)",java.util.regex.Matcher.quoteReplacement("]("+origin)+"$1)");
        Map<String,byte[]> bytes=new LinkedHashMap<>();
        List<String> warnings=new ArrayList<>();
        bytes.put("md",portable.getBytes(StandardCharsets.UTF_8));
        if(docx) {
            try { bytes.put("docx",renderer.docx(exported)); }
            catch(Exception failure) { warnings.add("DOCX 渲染失败，已跳过该附件"); }
        }
        if(pdf) {
            try { bytes.put("pdf",renderer.pdf(exported)); }
            catch(Exception failure) { warnings.add("PDF 渲染失败，已跳过该附件"); }
        }
        Map<String,String> paths=new LinkedHashMap<>();
        for(var entry:bytes.entrySet()) {
            String kind=entry.getKey();String mime=switch(kind){case "pdf"->"application/pdf";case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";default->"text/markdown; charset=utf-8";};
            String name=run+"."+kind;
            paths.put(kind,artifacts.save(entry.getValue(),name,kind,mime,report,run,"outputs/"+name,Map.of()).path("url").asText());
        }
        paths.put("json","/api/research-runs/"+run+"/log");
        return new Saved(Map.copyOf(paths),List.copyOf(warnings));
    }
}
