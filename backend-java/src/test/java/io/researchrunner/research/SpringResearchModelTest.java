package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringResearchModelTest {
    @Test void configuredRolesAreUsedWithoutFallbackAndUsageIsRecorded() throws Exception {
        var fast=mock(ChatModel.class);var smart=mock(ChatModel.class);var strategic=mock(ChatModel.class);
        var response=new ChatResponse(List.of(new Generation(new AssistantMessage("正文"))));
        when(fast.call(any(Prompt.class))).thenReturn(response);when(smart.call(any(Prompt.class))).thenReturn(response);when(strategic.call(any(Prompt.class))).thenReturn(response);
        var ledger=new UsageLedger();var model=new SpringResearchModel(
                new SpringResearchModel.Binding("test","fast",fast,null),new SpringResearchModel.Binding("test","smart",smart,null),
                new SpringResearchModel.Binding("test","strategic",strategic,null),ledger,new ObjectMapper());
        model.generate("planner","指令",Map.of());model.generate("writer","指令",Map.of());model.generate("section_reviewer","指令",Map.of());
        verify(fast).call(any(Prompt.class));verify(smart).call(any(Prompt.class));verify(strategic).call(any(Prompt.class));
        assertEquals(3,((List<?>)ledger.snapshot().get("model_calls")).size());assertNull(ledger.snapshot().get("model_cost_usd"));
        when(strategic.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider failed"));
        assertThrows(IllegalStateException.class,()->model.generate("fact_checker","指令",Map.of()));
        verifyNoMoreInteractions(fast,smart);
    }
}
