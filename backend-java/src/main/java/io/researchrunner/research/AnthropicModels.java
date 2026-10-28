package io.researchrunner.research;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.retry.support.RetryTemplate;

/**
 * A3-03: Anthropic Messages API adapter.
 *
 * <p>Uses Spring AI's native Anthropic client, so the request is {@code /v1/messages} with
 * {@code x-api-key} rather than the OpenAI-compatible path. Claude 4.x models do not accept a
 * temperature override in this project, matching the Python provider's suppression list.
 */
public final class AnthropicModels {
    private AnthropicModels() { }

    public static ChatModel create(String model, int maxTokens, Double temperature,
            String apiKey, String baseUrl) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Anthropic 模型不能为空");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("ANTHROPIC_API_KEY 未配置");
        if (maxTokens < 1) throw new IllegalArgumentException("输出预算必须为正数");
        var apiBuilder = AnthropicApi.builder().apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) {
            String normalized = baseUrl.replaceAll("/+$", "");
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                throw new IllegalArgumentException("ANTHROPIC_BASE_URL 无效");
            }
            apiBuilder.baseUrl(normalized);
        }
        var options = AnthropicChatOptions.builder().model(model).maxTokens(maxTokens);
        if (temperature != null && !suppressesTemperature(model)) {
            options.temperature(temperature);
        }
        return AnthropicChatModel.builder()
                .anthropicApi(apiBuilder.build())
                .defaultOptions(options.build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }

    static boolean suppressesTemperature(String model) {
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("claude-4") || lower.startsWith("claude-sonnet-4")
                || lower.startsWith("claude-opus-4") || lower.startsWith("claude-haiku-4");
    }
}
