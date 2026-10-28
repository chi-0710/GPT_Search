package io.researchrunner.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.research.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** A socket owns at most one run; feedback can never cross connection boundaries. */
@Component
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class ResearchSocket extends TextWebSocketHandler {
    private final ResearchExecutionService research;
    private final ObjectMapper json;
    private record Client(WebSocketSession socket,ResearchConnection run) {}
    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    public ResearchSocket(ResearchExecutionService research,ObjectMapper json) {this.research=research;this.json=json;}
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        var safe=new ConcurrentWebSocketSessionDecorator(session,10000,1024*1024);
        clients.put(session.getId(),new Client(safe,research.connection(event->safe.sendMessage(new TextMessage(json.writeValueAsString(event))))));
    }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception {
        var client=clients.get(session.getId());if(client==null)return;
        String text=message.getPayload();
        if(text.equals("ping")){client.socket().sendMessage(new TextMessage("pong"));return;}
        try {
            int split=text.indexOf(' ');
            if(split<0 && !text.startsWith("{"))throw new IllegalArgumentException("命令需要 JSON 参数");
            var body=json.readValue(text.startsWith("{")?text:text.substring(split+1),new TypeReference<Map<String,Object>>(){});
            String command=text.startsWith("{")?Objects.toString(body.get("type"),""):text.substring(0,split);
            switch(command) {
                case "start" -> {
                    body.put("generate_in_background",true);
                    research.report(body,client.run(),true);
                }
                case "human_feedback" -> {
                    var run=UUID.fromString(Objects.toString(body.get("run_id")));
                    Object value=body.get("round");
                    if(!(value instanceof Number number) || number.doubleValue()!=number.intValue()
                            || !client.run().feedback(run,number.intValue(),Objects.toString(body.get("request_id"),""),
                                Objects.toString(body.getOrDefault("feedback",body.get("content")),"")))throw new IllegalArgumentException("人工反馈已过期或不属于当前审核");
                }
                case "chat" -> throw new IllegalArgumentException("Java 报告跟进聊天尚未接入");
                default -> throw new IllegalArgumentException("未知 WebSocket 命令");
            }
        } catch(Exception error) {
            client.socket().sendMessage(new TextMessage(json.writeValueAsString(Map.of("type","error","output","请求无效或当前功能尚未接入"))));
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) throws InterruptedException {
        var client=clients.remove(session.getId());
        if(client!=null) {
            client.run().close();
            if(!client.run().awaitCleanup(java.time.Duration.ofSeconds(15)))
                org.slf4j.LoggerFactory.getLogger(ResearchSocket.class).warn("ResearchRunner: cancelled research still awaiting external request cleanup");
        }
    }
    @Override public void handleTransportError(WebSocketSession session,Throwable error) throws Exception {
        try{afterConnectionClosed(session,CloseStatus.SERVER_ERROR);}finally{session.close(CloseStatus.SERVER_ERROR);}
    }
}
