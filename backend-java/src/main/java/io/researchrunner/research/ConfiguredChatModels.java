package io.researchrunner.research;

import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredChatModels {
    private final Environment env;
    public ConfiguredChatModels(Environment env){this.env=env;}
    public SpringResearchModel.Binding reviewBinding(String role, String budgetKey) {
        return binding(role, env.getProperty(budgetKey, Integer.class,
                env.getProperty(role+"_TOKEN_LIMIT", Integer.class, 16000)), false);
    }
    public SpringResearchModel.Binding binding(String role,int tokens) {
        return binding(role,tokens,true);
    }
    private SpringResearchModel.Binding binding(String role,int tokens,boolean roleOverride) {
        String value=env.getProperty(role+"_LLM");if(value==null || !value.contains(":"))throw new IllegalArgumentException(role+"_LLM 必须显式配置供应商和模型");
        String[] parts=value.split(":",2);Map<String,String> variables=new HashMap<>();
        for(String name:List.of("OPENAI_API_KEY","OPENAI_BASE_URL","DEEPSEEK_API_KEY","DASHSCOPE_API_KEY","DASHSCOPE_BASE_URL","OPENROUTER_API_KEY","NEBIUS_API_KEY","NEBIUS_BASE_URL","ATLASCLOUD_API_KEY","VLLM_OPENAI_API_KEY","VLLM_OPENAI_API_BASE","AIMLAPI_API_KEY","FORGE_API_KEY","AVIAN_API_KEY","MINIMAX_API_KEY"))if(env.getProperty(name)!=null)variables.put(name,env.getProperty(name));
        var config=CompatibleModelFactory.settings(parts[0],parts[1],variables);
        return new SpringResearchModel.Binding(parts[0],parts[1],new CompatibleModelFactory().chat(config,(roleOverride ? env.getProperty(role+"_TOKEN_LIMIT",Integer.class,tokens) : tokens),env.getProperty("TEMPERATURE",Double.class,0.4)),null);
    }
}
