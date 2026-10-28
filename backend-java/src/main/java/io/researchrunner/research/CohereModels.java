package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A3-08: native Cohere Chat adapter ({@code POST /v2/chat}).
 *
 * <p>Mirrors {@code langchain_cohere.ChatCohere} in the Python provider: Bearer authentication,
 * {@code messages} with roles, {@code message.content[].text} on the response and real usage
 * tokens. Streaming is intentionally not implemented; the research graph only calls
 * {@link ChatModel#call(Prompt)}.
 */
public final class CohereModels {
    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private CohereModels() { }

    public static ChatModel create(String model, int maxTokens, Double temperature,
            String apiKey, String baseUrl) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Cohere 模型不能为空");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("COHERE_API_KEY 未配置");
        if (maxTokens < 1) throw new IllegalArgumentException("输出预算必须为正数");
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://api.cohere.com" : CustomChatSupport.trimTrailingSlash(baseUrl.strip());
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("COHERE_BASE_URL 无效");
        }
        URI endpoint = URI.create(base + "/v2/chat");
        return new CohereChatModel(model, maxTokens, temperature, apiKey, endpoint,
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), new ObjectMapper());
    }

    private record CohereChatModel(String model, int maxTokens, Double temperature, String apiKey,
            URI endpoint, HttpClient client, ObjectMapper json) implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            if (temperature != null) body.put("temperature", temperature);
            List<Map<String, Object>> messages = new ArrayList<>();
            for (var message : CustomChatSupport.messages(prompt)) {
                messages.add(Map.of("role", message.role(), "content", message.content()));
            }
            body.put("messages", messages);
            try {
                JsonNode root = CustomChatSupport.postJson(client, endpoint, TIMEOUT,
                        Map.of("Authorization", "Bearer " + apiKey), body, json, "Cohere");
                String text = textContent(root.path("message").path("content"));
                Integer input = CustomChatSupport.integer(
                        root.path("usage").path("tokens"), "input_tokens");
                Integer output = CustomChatSupport.integer(
                        root.path("usage").path("tokens"), "output_tokens");
                if (input == null) input = CustomChatSupport.integer(
                        root.path("usage").path("billed_units"), "input_tokens");
                if (output == null) output = CustomChatSupport.integer(
                        root.path("usage").path("billed_units"), "output_tokens");
                return CustomChatSupport.response(text, model, finishReason(root), input, output);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Cohere 请求失败：" + e.getMessage(), e);
            }
        }
    }

    private static String textContent(JsonNode content) {
        if (content == null || !content.isArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonNode item : content) {
            String type = CustomChatSupport.text(item, "type");
            if (!type.isEmpty() && !"text".equals(type)) continue;
            text.append(CustomChatSupport.text(item, "text"));
        }
        return text.toString();
    }

    private static String finishReason(JsonNode root) {
        String reason = CustomChatSupport.text(root, "finish_reason");
        return switch (reason.toUpperCase(java.util.Locale.ROOT)) {
            case "COMPLETE", "END_TURN", "" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "TOOL_CALL" -> "tool_calls";
            default -> reason.toLowerCase(java.util.Locale.ROOT);
        };
    }
}
