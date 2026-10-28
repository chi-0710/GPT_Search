package io.researchrunner.research;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.KeyCredential;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.model.ChatModel;

/**
 * A3-05: Azure OpenAI adapter.
 *
 * <p>Deployment name is the model segment of {@code azure_openai:<deployment>}. Endpoint and key
 * come from {@code AZURE_OPENAI_ENDPOINT} / {@code AZURE_OPENAI_API_KEY}; an optional
 * {@code AZURE_OPENAI_API_VERSION} is passed through the SDK's service-version query parameter.
 */
public final class AzureModels {
    private AzureModels() { }

    public static ChatModel create(String deployment, int maxTokens, Double temperature,
            String endpoint, String apiKey) {
        if (deployment == null || deployment.isBlank()) throw new IllegalArgumentException("Azure 部署名不能为空");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("AZURE_OPENAI_API_KEY 未配置");
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("AZURE_OPENAI_ENDPOINT 未配置");
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            throw new IllegalArgumentException("AZURE_OPENAI_ENDPOINT 无效");
        }
        if (maxTokens < 1) throw new IllegalArgumentException("输出预算必须为正数");
        var clientBuilder = new OpenAIClientBuilder()
                .endpoint(endpoint.replaceAll("/+$", ""))
                .credential(new KeyCredential(apiKey));
        var options = AzureOpenAiChatOptions.builder().deploymentName(deployment).maxTokens(maxTokens);
        if (temperature != null) options.temperature(temperature);
        return AzureOpenAiChatModel.builder()
                .openAIClientBuilder(clientBuilder)
                .defaultOptions(options.build())
                .build();
    }
}
