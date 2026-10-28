package io.researchrunner.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.researchrunner.research.ResearchExecutionService;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A2-11: /api/multi_agents keeps Python's fixed demo request shape. */
class ResearchControllerDemoTest {
    @Test
    void multiAgentsUsesTheSameDemoRequestAsPython() throws Exception {
        var service = mock(ResearchExecutionService.class);
        when(service.report(anyMap())).thenReturn(Map.of("ok", true));
        new ResearchController(service).defaultResearch();

        verify(service).report(argThat(request ->
                "Is AI in a hype cycle?".equals(request.get("task"))
                        && "multi_agents".equals(request.get("report_type"))
                        && "web".equals(request.get("report_source"))
                        && "Objective".equals(request.get("tone"))
                        && Boolean.FALSE.equals(request.get("generate_in_background"))));
        assertEquals(true, new ResearchController(service).defaultResearch().get("ok"));
    }
}
