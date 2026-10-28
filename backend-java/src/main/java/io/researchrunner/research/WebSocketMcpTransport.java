package io.researchrunner.research;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * A5-02: MCP JSON-RPC over WebSocket transport.
 *
 * <p>The MCP Java SDK 0.18.4 ships stdio/SSE/streamable-HTTP only; Python supports {@code ws://}
 * and {@code wss://}. This adapter maps the SDK transport contract onto
 * {@link java.net.http.WebSocket} so the existing client, initialize and tool-call code is reused.
 */
public final class WebSocketMcpTransport implements McpClientTransport {
    private final URI uri;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
    private volatile WebSocket socket;
    private volatile Consumer<Throwable> exceptionHandler = error -> { };

    public WebSocketMcpTransport(URI uri, Map<String, String> headers, Duration timeout) {
        if (!("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) throw new IllegalArgumentException("MCP WebSocket 地址无效");
        this.uri = uri;
        this.headers = Map.copyOf(headers);
        this.timeout = timeout;
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.create(sink -> {
            var builder = HttpClient.newBuilder().connectTimeout(timeout).build().newWebSocketBuilder();
            headers.forEach(builder::header);
            builder.connectTimeout(timeout).buildAsync(uri, new Listener(handler, sink))
                    .whenComplete((webSocket, error) -> {
                        if (error != null) sink.error(error);
                        else socket = webSocket;
                    });
        });
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.create(sink -> {
            WebSocket active = socket;
            if (active == null) {
                sink.error(new IllegalStateException("MCP WebSocket 尚未连接"));
                return;
            }
            try {
                active.sendText(mapper.writeValueAsString(message), true)
                        .whenComplete((ignored, error) -> {
                            if (error != null) sink.error(error);
                            else sink.success();
                        });
            } catch (Exception error) {
                sink.error(error);
            }
        });
    }

    @Override
    public <T> T unmarshalFrom(Object value, io.modelcontextprotocol.json.TypeRef<T> typeRef) {
        return mapper.convertValue(value, typeRef);
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        if (handler != null) this.exceptionHandler = handler;
    }

    @Override
    public void close() {
        WebSocket active = socket;
        if (active != null) active.abort();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.create(sink -> {
            WebSocket active = socket;
            if (active == null) {
                sink.success();
                return;
            }
            active.sendClose(WebSocket.NORMAL_CLOSURE, "mcp websocket closed")
                    .whenComplete((ignored, error) -> sink.success());
        });
    }

    private final class Listener implements WebSocket.Listener {
        private final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;
        private final MonoSink<Void> sink;
        private final StringBuilder buffer = new StringBuilder();

        private Listener(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler,
                MonoSink<Void> sink) {
            this.handler = handler;
            this.sink = sink;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            sink.success();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                try {
                    McpSchema.JSONRPCMessage message = mapper.readValue(payload, McpSchema.JSONRPCMessage.class);
                    handler.apply(Mono.just(message)).subscribe(response -> {
                        if (response == null) return;
                        try {
                            webSocket.sendText(mapper.writeValueAsString(response), true);
                        } catch (Exception error) {
                            exceptionHandler.accept(error);
                        }
                    }, exceptionHandler);
                } catch (Exception error) {
                    exceptionHandler.accept(error);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            exceptionHandler.accept(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null;
            return null;
        }
    }
}
