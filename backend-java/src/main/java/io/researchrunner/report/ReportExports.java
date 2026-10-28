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
    @Transactional(rollbackFor=Exception.class)
    public Map<String,String> save(String report,UUID run,String markdown,boolean pdf,boolean docx) throws Exception {
        String exported=markdown.replaceAll("\\]\\((/api/research-runs/[0-9a-fA-F-]{36}/evidence/\\d+/view)\\)",java.util.regex.Matcher.quoteReplacement("]("+origin)+"$1)");
        var renderer=new ReportRenderer(id->{var file=resources.artifact(id);return file==null?null:file.content();});
        Map<String,byte[]> bytes=new LinkedHashMap<>();bytes.put("md",exported.getBytes(StandardCharsets.UTF_8));
        if(docx)bytes.put("docx",renderer.docx(exported));if(pdf)bytes.put("pdf",renderer.pdf(exported));
        Map<String,String> paths=new LinkedHashMap<>();
        for(var entry:bytes.entrySet()) {
            String kind=entry.getKey();String mime=switch(kind){case "pdf"->"application/pdf";case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";default->"text/markdown; charset=utf-8";};
            String name=run+"."+kind;
            paths.put(kind,artifacts.save(entry.getValue(),name,kind,mime,report,run,"outputs/"+name,Map.of()).path("url").asText());
        }
        paths.put("json","/api/research-runs/"+run+"/log");return paths;
    }
}
