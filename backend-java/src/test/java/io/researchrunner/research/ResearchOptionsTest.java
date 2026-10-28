package io.researchrunner.research;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ResearchOptionsTest {
    @Test void preservesDefaultsAndNestedLimitsWithoutMutableGuidelines() {
        var values=new ArrayList<>(List.of("来源必须可信"));
        var task=new HashMap<String,Object>();task.put("guidelines",values);task.put("follow_guidelines",true);
        task.put("max_draft_revisions",null);task.put("max_plan_revisions",0);task.put("max_sections",2);
        var parsed=ResearchOptions.parse(Map.of("task_options",task),new MockEnvironment(),"问题","Objective");
        values.clear();assertEquals(List.of("来源必须可信"),parsed.nodes().guidelines());
        assertTrue(parsed.nodes().followGuidelines());assertNull(parsed.nodes().draftLimit());
        assertEquals(0,parsed.planLimit());assertEquals(2,parsed.nodes().maxSections());assertFalse(parsed.humanReview());
    }
    @Test void rejectsMalformedOptionsInsteadOfSilentlyUsingDefaults() {
        for(var request:List.of(Map.<String,Object>of("max_sections",1.5),Map.<String,Object>of("max_plan_revisions",-1),
                Map.<String,Object>of("include_human_feedback","true"),Map.<String,Object>of("guidelines",List.of(12)))) {
            assertThrows(IllegalArgumentException.class,()->ResearchOptions.parse(request,new MockEnvironment(),"问题","Objective"));
        }
    }
}
