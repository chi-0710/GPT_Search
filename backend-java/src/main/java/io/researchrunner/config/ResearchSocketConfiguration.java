package io.researchrunner.config;

import io.researchrunner.api.ResearchSocket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@EnableWebSocket
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class ResearchSocketConfiguration implements WebSocketConfigurer {
    private final ResearchSocket socket;
    private final String[] origins;
    public ResearchSocketConfiguration(ResearchSocket socket,@Value("${researchrunner.cors-origins}") String origins) {
        this.socket=socket;this.origins=java.util.Arrays.stream(origins.split(",")).map(String::strip).toArray(String[]::new);
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket,"/ws").setAllowedOrigins(origins);
    }
}
