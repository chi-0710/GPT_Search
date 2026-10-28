package io.researchrunner;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResearchCliTest {
    @Test void mapsLegacyArgumentsToSharedResearchRequest() {
        var request=ResearchCli.parse(new String[]{"中文问题","--report_type","deep","--report_source=hybrid",
                "--tone","analytical","--encoding","utf-8","--query_domains","example.com, example.org","--no-pdf","--no-docx"});
        assertEquals("multi_agents",request.get("report_type"));assertEquals("Analytical",request.get("tone"));
        assertEquals(List.of("example.com","example.org"),request.get("query_domains"));
        assertEquals(false,request.get("export_pdf"));assertEquals(false,request.get("export_docx"));
        assertEquals(false,request.get("generate_in_background"));
    }
    @Test void invalidArgumentsFailBeforeStartingDatabaseOrProvider() {
        for(String[] args:List.of(new String[]{},new String[]{"topic","--report_source","unknown"},
                new String[]{"topic","--tone"},new String[]{"topic","--unknown","value"}))
            assertThrows(IllegalArgumentException.class,()->ResearchCli.parse(args));
        assertEquals(0,ResearchCli.run(new String[]{"--help"}));
    }
}
