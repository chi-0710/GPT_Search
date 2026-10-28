package io.researchrunner.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Shared plumbing for native (non-OpenAI) chat adapters: prompt flattening, bounded HTTP JSON
 * requests and Spring AI {@link ChatResponse} assembly with real usage metadata.
 */
final class CustomChatSupport {
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    record ChatMessage(String role, String content) { }

    private CustomChatSupport() { }

    static List<ChatMessage> messages(Prompt prompt) {
        List<ChatMessage> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            String role = switch (message.getMessageType()) {
                case SYSTEM -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                case TOOL -> "tool";
            };
            messages.add(new ChatMessage(role, message.getText() == null ? "" : message.getText()));
        }
        return messages;
    }

    static String systemText(Prompt prompt) {
        StringBuilder text = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            if (message.getMessageType() != MessageType.SYSTEM) continue;
            if (text.length() > 0) text.append("\n\n");
            text.append(message.getText() == null ? "" : message.getText());
        }
        return text.toString();
    }

    static List<ChatMessage> conversation(Prompt prompt) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage message : messages(prompt)) {
            if (!message.role().equals("system")) result.add(message);
        }
        return result;
    }

    static ChatResponse response(String text, String model, String finishReason,
            Integer promptTokens, Integer completionTokens) {
        var generation = new Generation(new AssistantMessage(text == null ? "" : text),
                ChatGenerationMetadata.builder()
                        .finishReason(finishReason == null || finishReason.isBlank() ? "stop" : finishReason)
                        .build());
        var metadata = ChatResponseMetadata.builder().model(model)
                .usage(new DefaultUsage(promptTokens, completionTokens)).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    static JsonNode postJson(HttpClient client, URI uri, Duration timeout,
            Map<String, String> headers, Map<String, Object> body, ObjectMapper json,
            String provider) throws Exception {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return readJson(response, json, provider);
    }

    private static JsonNode readJson(HttpResponse<InputStream> response, ObjectMapper json,
            String provider) throws Exception {
        try (InputStream stream = response.body()) {
            byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException(provider + " 响应过大");
            if (response.statusCode() != 200) {
                throw new IllegalStateException(provider + " 请求失败，HTTP " + response.statusCode());
            }
            return json.readTree(bytes);
        }
    }

    /** Text value of a field, treating an explicit JSON null exactly like a missing field. */
    static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        return value.asText("");
    }

    static Integer integer(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) continue;
            if (value.isNumber()) return value.asInt();
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().strip());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
