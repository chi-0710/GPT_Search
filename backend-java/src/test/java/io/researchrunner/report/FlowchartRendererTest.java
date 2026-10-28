package io.researchrunner.report;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowchartRendererTest {
    @Test void rendersChineseNodesAndDirectedEdgesWithoutExternalRuntime() throws Exception {
        byte[] png=new FlowchartRenderer().png("```mermaid\nflowchart TD\nA[收集证据] --> B[人工审核]\nB --> C[发布报告]\n```");
        var image=ImageIO.read(new ByteArrayInputStream(png));assertEquals(1360,image.getWidth());assertTrue(image.getHeight()>200);
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target","flowchart-preview.png"),png);
    }
    @Test void unsupportedOrExecutableSyntaxFailsInsteadOfBeingDropped() {
        for(String diagram:java.util.List.of("flowchart TD\nA --> B","flowchart TD\nA[内容]\nclick A call()","pie\n\"data\": 5"))
            assertThrows(IllegalArgumentException.class,()->new FlowchartRenderer().png(diagram));
    }
}
