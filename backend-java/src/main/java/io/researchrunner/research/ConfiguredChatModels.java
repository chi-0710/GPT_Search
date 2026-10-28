package io.researchrunner.research;

import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredChatModels {
    private final Environment env;
    public ConfiguredChatModels(Environment env){this.env=env;}

    /** Environment keys forwarded to the native / OpenAI-compatible adapters. */
    private static final List<String> PROVIDER_VARIABLES = List.of(
            "OPENAI_API_KEY","OPENAI_BASE_URL","DEEPSEEK_API_KEY","DASHSCOPE_API_KEY","DASHSCOPE_BASE_URL",
            "OPENROUTER_API_KEY","NEBIUS_API_KEY","NEBIUS_BASE_URL","ATLASCLOUD_API_KEY",
            "VLLM_OPENAI_API_KEY","VLLM_OPENAI_API_BASE","AIMLAPI_API_KEY","FORGE_API_KEY","AVIAN_API_KEY",
            "MINIMAX_API_KEY","OLLAMA_BASE_URL","GROQ_API_KEY","GROQ_BASE_URL","MISTRAL_API_KEY",
            "MISTRAL_BASE_URL","XAI_API_KEY","XAI_BASE_URL","GOOGLE_API_KEY","GOOGLE_GENAI_BASE_URL",
            "FIREWORKS_API_KEY","FIREWORKS_BASE_URL","TOGETHER_API_KEY","TOGETHER_BASE_URL",
            "LITELLM_API_KEY","LITELLM_BASE_URL","NETMIND_API_KEY","NETMIND_API_TOKEN","NETMIND_BASE_URL",
            "HUGGINGFACE_API_KEY","HF_TOKEN","HUGGINGFACE_BASE_URL",
            "COHERE_API_KEY","COHERE_BASE_URL",
            "GOOGLE_APPLICATION_CREDENTIALS","GOOGLE_VERTEX_CREDENTIALS","GOOGLE_CLOUD_PROJECT",
            "GOOGLE_VERTEX_PROJECT","GOOGLE_CLOUD_REGION","GOOGLE_VERTEX_LOCATION","GOOGLE_CLOUD_LOCATION",
            "GOOGLE_VERTEX_BASE_URL",
            "AWS_ACCESS_KEY_ID","AWS_ACCESS_KEY","AWS_SECRET_ACCESS_KEY","AWS_SECRET_KEY",
            "AWS_SESSION_TOKEN","AWS_REGION","AWS_DEFAULT_REGION","BEDROCK_BASE_URL");

    public SpringResearchModel.Binding binding(String role,int tokens) {
        return binding(role,tokens,Map.of());
    }

    /** Applies the supported {@code LLM_KWARGS} overrides (temperature, max_tokens) per role. */
    public SpringResearchModel.Binding binding(String role,int tokens,Map<String,Object> kwargs) {
        String value=env.getProperty(role+"_LLM");if(value==null || !value.contains(":"))throw new IllegalArgumentException(role+"_LLM 必须显式配置供应商和模型");
        String[] parts=value.split(":",2);
        ProviderCatalog.require(ProviderCatalog.Kind.CHAT,parts[0]);
        int maxTokens=env.getProperty(role+"_TOKEN_LIMIT",Integer.class,tokens);
        double temperature=env.getProperty("TEMPERATURE",Double.class,0.4);
        if(kwargs.get("max_tokens") instanceof Number number)maxTokens=number.intValue();
        if(kwargs.get("temperature") instanceof Number number)temperature=number.doubleValue();

        if(parts[0].equals("anthropic")) {
            var client=AnthropicModels.create(parts[1],maxTokens,temperature,
                    env.getProperty("ANTHROPIC_API_KEY"),env.getProperty("ANTHROPIC_BASE_URL"));
            return new SpringResearchModel.Binding(parts[0],parts[1],client,null);
        }
        if(parts[0].equals("azure_openai")) {
            var client=AzureModels.create(parts[1],maxTokens,temperature,
                    env.getProperty("AZURE_OPENAI_ENDPOINT"),env.getProperty("AZURE_OPENAI_API_KEY"));
            return new SpringResearchModel.Binding(parts[0],parts[1],client,null);
        }

        Map<String,String> variables=providerVariables();
        if(parts[0].equals("ollama") && !variables.containsKey("OLLAMA_API_KEY")) variables.put("OLLAMA_API_KEY","ollama");
        if(!variables.containsKey("NETMIND_API_KEY") && variables.containsKey("NETMIND_API_TOKEN"))
            variables.put("NETMIND_API_KEY",variables.get("NETMIND_API_TOKEN"));
        if(!variables.containsKey("HUGGINGFACE_API_KEY") && variables.containsKey("HF_TOKEN"))
            variables.put("HUGGINGFACE_API_KEY",variables.get("HF_TOKEN"));

        if(parts[0].equals("cohere")) {
            var client=CohereModels.create(parts[1],maxTokens,temperature,
                    variables.get("COHERE_API_KEY"),variables.get("COHERE_BASE_URL"));
            return new SpringResearchModel.Binding(parts[0],parts[1],client,null);
        }
        if(parts[0].equals("google_vertexai")) {
            var client=VertexModels.create(parts[1],maxTokens,temperature,variables);
            return new SpringResearchModel.Binding(parts[0],parts[1],client,null);
        }
        if(parts[0].equals("bedrock")) {
            var client=BedrockModels.create(parts[1],maxTokens,temperature,variables);
            return new SpringResearchModel.Binding(parts[0],parts[1],client,null);
        }

        var config=CompatibleModelFactory.settings(parts[0],parts[1],variables);
        return new SpringResearchModel.Binding(parts[0],parts[1],
                new CompatibleModelFactory().chat(config,maxTokens,temperature),null);
    }

    private Map<String,String> providerVariables() {
        Map<String,String> variables=new HashMap<>();
        for(String name:PROVIDER_VARIABLES) {
            String value=env.getProperty(name);
            if(value!=null) variables.put(name,value);
        }
        return variables;
    }
}
