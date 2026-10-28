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
    private final io.researchrunner.report.ReportChat chat;
    private final java.util.concurrent.ExecutorService chatExecutor=new java.util.concurrent.ThreadPoolExecutor(2,2,0,
            java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(32),Thread.ofVirtual().factory(),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final Map<String,java.util.concurrent.Future<?>> chatTasks=new ConcurrentHashMap<>();
    private record Client(WebSocketSession socket,ResearchConnection run) {}
    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    public ResearchSocket(ResearchExecutionService research,ObjectMapper json,io.researchrunner.report.ReportChat chat) {this.research=research;this.json=json;this.chat=chat;}
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
                case "chat" -> {
                    if(!body.containsKey("messages") && body.get("message") instanceof String question)
                        body.put("messages",List.of(Map.of("role","user","content",question)));
                    body.putIfAbsent("report","");
                    var task=new java.util.concurrent.FutureTask<Void>(()->{
                        try {
                            var answer=json.valueToTree(chat.chat(json.valueToTree(body)).get("response"));
                            ((com.fasterxml.jackson.databind.node.ObjectNode)answer).put("type","chat");
                            if(client.socket().isOpen() && !Thread.currentThread().isInterrupted())
                                client.socket().sendMessage(new TextMessage(json.writeValueAsString(answer)));
                        } catch(Exception error) {
                            if(client.socket().isOpen() && !Thread.currentThread().isInterrupted())
                                client.socket().sendMessage(new TextMessage(json.writeValueAsString(Map.of("type","error","output","聊天请求失败"))));
                        }
                        return null;
                    }) {
                        @Override protected void done(){chatTasks.remove(session.getId(),this);}
                    };
                    if(chatTasks.putIfAbsent(session.getId(),task)!=null)throw new IllegalArgumentException("聊天请求仍在执行");
                    try{chatExecutor.execute(task);}catch(java.util.concurrent.RejectedExecutionException error){task.cancel(true);throw error;}
                }
                default -> throw new IllegalArgumentException("未知 WebSocket 命令");
            }
        } catch(Exception error) {
            client.socket().sendMessage(new TextMessage(json.writeValueAsString(Map.of("type","error","output","请求无效或当前功能尚未接入"))));
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) throws InterruptedException {
        var client=clients.remove(session.getId());
        var task=chatTasks.remove(session.getId());if(task!=null)task.cancel(true);
        if(client!=null) {
            client.run().close();
            if(!client.run().awaitCleanup(java.time.Duration.ofSeconds(15)))
                org.slf4j.LoggerFactory.getLogger(ResearchSocket.class).warn("ResearchRunner: cancelled research still awaiting external request cleanup");
        }
    }
    @Override public void handleTransportError(WebSocketSession session,Throwable error) throws Exception {
        try{afterConnectionClosed(session,CloseStatus.SERVER_ERROR);}finally{session.close(CloseStatus.SERVER_ERROR);}
    }
    @jakarta.annotation.PreDestroy public void close() throws InterruptedException {
        for(var task:chatTasks.values())task.cancel(true);
        chatExecutor.shutdownNow();chatExecutor.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS);
    }
}
