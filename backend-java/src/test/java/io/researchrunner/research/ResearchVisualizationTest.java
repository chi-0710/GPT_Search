package io.researchrunner.research;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResearchVisualizationTest {
    @Test void usesActualEvidenceAndAppendsDiagram() throws Exception {
        var registry=new EvidenceRegistry(UUID.randomUUID());
        registry.register(List.of(Map.of("url","https://example.com","raw_content","研究流程证据")),"web");
        var renderer=new ResearchVisualization((stage,prompt,input)->{
            assertEquals("visualizer",stage);assertEquals(registry.snapshots(),input.get("evidence"));
            return "```mermaid\nflowchart LR\n A[检索] --> B[验证]\n```";
        },registry,(stage,message)->{});
        assertTrue(renderer.render("正文").contains("## 可视化"));
    }
    @Test void acceptsOnlyExactNoneAndRejectsExecutableContent() throws Exception {
        var registry=new EvidenceRegistry(UUID.randomUUID());
        for(String output:List.of("None and a chart","```mermaid\nflowchart LR\nclick A call()\n```",
                "```mermaid\n%%{init: {}}%%\nflowchart LR\n```")) {
            assertThrows(IllegalStateException.class,()->new ResearchVisualization((s,p,i)->output,registry,(s,m)->{}).render("正文"));
        }
        assertEquals("正文",new ResearchVisualization((s,p,i)->"None",registry,(s,m)->{}).render("正文"));
    }
}
