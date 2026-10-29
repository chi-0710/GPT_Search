package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/** Role selection uses supplied configured clients; no provider fallback or hidden default model. */
public final class SpringResearchModel implements ResearchNodes.Model {
    public record Binding(String provider,String model,ChatModel client,UsageLedger.Price price) {
        public Binding {if(provider==null || model==null || client==null)throw new IllegalArgumentException("模型绑定不完整");}
    }
    private static final Set<String> STRATEGIC=Set.of("planner","fact_checker");
    private static final Set<String> FAST=Set.of("section_reviewer");
    private final Binding fast,smart,strategic;
    private final Map<String,Binding> stageBindings;
    private final UsageLedger usage;
    private final ObjectMapper json;
    public SpringResearchModel(Binding fast,Binding smart,Binding strategic,UsageLedger usage,ObjectMapper json) {
        this(fast,smart,strategic,usage,json,Map.of());
    }
    public SpringResearchModel(Binding fast,Binding smart,Binding strategic,UsageLedger usage,ObjectMapper json,Map<String,Binding> stageBindings) {
        this.stageBindings=Map.copyOf(stageBindings);
        this.fast=fast;this.smart=smart;this.strategic=strategic;this.usage=usage;this.json=json;
    }
    @Override public String generate(String stage,String instruction,Map<String,Object> input) throws Exception {
        Binding binding=stageBindings.getOrDefault(stage,STRATEGIC.contains(stage)?strategic:FAST.contains(stage)?fast:smart);
        var result=new SpringModelGateway(binding.client()).call(new Prompt(List.of(new SystemMessage(instruction),new UserMessage(json.writeValueAsString(input)))));
        var tokens=result.getMetadata().getUsage();
        usage.record(stage,binding.provider(),binding.model(),tokens==null?null:tokens.getPromptTokens(),tokens==null?null:tokens.getCompletionTokens(),binding.price());
        if(result.getResult()==null)throw new IllegalStateException("模型未返回生成结果");
        String finish=result.getResult().getMetadata().getFinishReason();
        if("length".equalsIgnoreCase(finish) || "max_tokens".equalsIgnoreCase(finish))throw new IllegalStateException("模型输出达到长度上限（阶段："+stage+"，模型："+binding.model()+"），未使用截断内容继续研究");
        return result.getResult().getOutput().getText();
    }
}
