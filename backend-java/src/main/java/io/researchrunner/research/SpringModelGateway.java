package io.researchrunner.research;

import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Model boundary shared by graph nodes; callers retain provider usage metadata. */
public final class SpringModelGateway {
    private final ChatModel model;

    public SpringModelGateway(ChatModel model) {
        this.model = Objects.requireNonNull(model);
    }

    public ChatResponse call(Prompt prompt) {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("研究已取消");
        }
        return model.call(prompt);
    }

    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> model.stream(prompt));
    }
}
