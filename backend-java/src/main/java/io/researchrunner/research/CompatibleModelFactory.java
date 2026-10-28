package io.researchrunner.research;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** Explicit adapters for providers already using the OpenAI protocol in the Python implementation. */
public final class CompatibleModelFactory {
    private record Endpoint(String url, String key, String override) { }
    private static final Map<String, Endpoint> ENDPOINTS = Map.ofEntries(
        Map.entry("openai", new Endpoint("https://api.openai.com/v1", "OPENAI_API_KEY", "OPENAI_BASE_URL")),
        Map.entry("deepseek", new Endpoint("https://api.deepseek.com", "DEEPSEEK_API_KEY", null)),
        Map.entry("dashscope", new Endpoint("https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", "DASHSCOPE_BASE_URL")),
        Map.entry("atlascloud", new Endpoint("https://api.atlascloud.ai/v1", "ATLASCLOUD_API_KEY", null)),
        Map.entry("openrouter", new Endpoint("https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", null)),
        Map.entry("vllm_openai", new Endpoint(null, "VLLM_OPENAI_API_KEY", "VLLM_OPENAI_API_BASE")),
        Map.entry("aimlapi", new Endpoint("https://api.aimlapi.com/v1", "AIMLAPI_API_KEY", null)),
        Map.entry("forge", new Endpoint("https://api.forge.tensorblock.co/v1", "FORGE_API_KEY", null)),
        Map.entry("avian", new Endpoint("https://api.avian.io/v1", "AVIAN_API_KEY", null)),
        Map.entry("minimax", new Endpoint("https://api.minimax.io/v1", "MINIMAX_API_KEY", null)),
        Map.entry("nebius", new Endpoint("https://api.tokenfactory.nebius.com/v1", "NEBIUS_API_KEY", "NEBIUS_BASE_URL")),
        Map.entry("ollama", new Endpoint("http://localhost:11434/v1", "OLLAMA_API_KEY", "OLLAMA_BASE_URL")),
        Map.entry("groq", new Endpoint("https://api.groq.com/openai/v1", "GROQ_API_KEY", "GROQ_BASE_URL")),
        Map.entry("mistralai", new Endpoint("https://api.mistral.ai/v1", "MISTRAL_API_KEY", "MISTRAL_BASE_URL")),
        Map.entry("xai", new Endpoint("https://api.x.ai/v1", "XAI_API_KEY", "XAI_BASE_URL")),
        Map.entry("google_genai", new Endpoint("https://generativelanguage.googleapis.com/v1beta/openai",
                "GOOGLE_API_KEY", "GOOGLE_GENAI_BASE_URL")),
        Map.entry("fireworks", new Endpoint("https://api.fireworks.ai/inference/v1",
                "FIREWORKS_API_KEY", "FIREWORKS_BASE_URL")),
        Map.entry("together", new Endpoint("https://api.together.xyz/v1",
                "TOGETHER_API_KEY", "TOGETHER_BASE_URL")),
        Map.entry("litellm", new Endpoint(null, "LITELLM_API_KEY", "LITELLM_BASE_URL")),
        Map.entry("netmind", new Endpoint("https://api.netmind.ai/inference-api/openai/v1",
                "NETMIND_API_KEY", "NETMIND_BASE_URL")),
        Map.entry("huggingface", new Endpoint("https://router.huggingface.co/v1",
                "HUGGINGFACE_API_KEY", "HUGGINGFACE_BASE_URL"))
    );

    public record Settings(String provider, String model, URI endpoint, String apiKey, Duration timeout) {
        public Settings {
            if (model == null || model.isBlank() || apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("模型或服务密钥未配置");
            if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))) {
                throw new IllegalArgumentException("模型服务地址无效");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("请求超时必须为正数");
        }
        @Override public String toString() { return "ModelSettings[provider=" + provider + ", model=" + model + ", credentials=REDACTED]"; }
    }

    public static Settings settings(String provider, String model, Map<String, String> environment) {
        Endpoint definition = ENDPOINTS.get(provider);
        if (definition == null) throw new IllegalArgumentException("此适配器不支持供应商：" + provider);
        String override = definition.override() == null ? null : environment.get(definition.override());
        String endpoint = override == null || override.isBlank() ? definition.url() : override;
        if (endpoint == null) throw new IllegalArgumentException("模型服务地址未配置");
        return new Settings(provider, model, URI.create(endpoint), environment.get(definition.key()), Duration.ofSeconds(180));
    }

    public OpenAiChatModel chat(Settings settings, int tokens, Double temperature) {
        if (tokens < 1) throw new IllegalArgumentException("输出预算必须为正数");
        var options = OpenAiChatOptions.builder().model(settings.model());
        boolean reasoning = settings.model().matches("^(o[134]|gpt-5).*" );
        if (reasoning) options.maxCompletionTokens(tokens);
        else options.maxTokens(tokens);
        if (!reasoning && !settings.model().contains("deepseek-reasoner")) options.temperature(temperature);
        return OpenAiChatModel.builder().openAiApi(api(settings)).defaultOptions(options.build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    public OpenAiEmbeddingModel embedding(Settings settings, int dimensions) {
        if (dimensions < 1) throw new IllegalArgumentException("向量维度必须为正数");
        return new OpenAiEmbeddingModel(api(settings), MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(settings.model()).dimensions(dimensions).build(),
                RetryTemplate.builder().maxAttempts(1).build());
    }

    private OpenAiApi api(Settings settings) {
        URI endpoint = settings.endpoint();
        String origin = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String prefix = endpoint.getRawPath().replaceAll("/+$", "");
        var client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(settings.timeout());
        return OpenAiApi.builder().baseUrl(origin).apiKey(settings.apiKey())
                .completionsPath(prefix + "/chat/completions").embeddingsPath(prefix + "/embeddings")
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(client)))
                .build();
    }
}
