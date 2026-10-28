package io.researchrunner.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.report.ReportChat;
import io.researchrunner.research.*;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchSocketChatTest {
    @Test void chatDoesNotBlockHeartbeatAndPreservesLegacyEventShape() throws Exception {
        var json=new ObjectMapper();var service=mock(ResearchExecutionService.class);var chat=mock(ReportChat.class);
        var run=mock(ResearchConnection.class);when(service.connection(any())).thenReturn(run);
        when(run.awaitCleanup(any())).thenReturn(true);
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn("connection");when(session.isOpen()).thenReturn(true);
        var sent=new LinkedBlockingQueue<String>();doAnswer(call->{sent.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(session).sendMessage(any());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(chat.chat(any())).thenAnswer(call->{entered.countDown();release.await();return Map.of("response",Map.of("role","assistant","content","回答","metadata",Map.of("tool_calls",java.util.List.of())));});
        var handler=new ResearchSocket(service,json,chat);handler.afterConnectionEstablished(session);
        try {
            handler.handleTextMessage(session,new TextMessage("chat {\"message\":\"问题\"}"));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            handler.handleTextMessage(session,new TextMessage("ping"));assertEquals("pong",sent.poll(3,TimeUnit.SECONDS));
            release.countDown();var reply=json.readTree(sent.poll(3,TimeUnit.SECONDS));
            assertEquals("chat",reply.path("type").asText());assertEquals("回答",reply.path("content").asText());
            assertTrue(reply.path("metadata").has("tool_calls"));
        } finally {release.countDown();handler.afterConnectionClosed(session,CloseStatus.NORMAL);handler.close();}
    }
}
