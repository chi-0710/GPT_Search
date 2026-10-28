package io.researchrunner.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.researchrunner.research.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportChatTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void nativeToolProtocolSearchesOnceAndPersistsSourcesWithHistory(boolean exhausted) throws Exception {
        var json=new ObjectMapper();var calls=new AtomicInteger();var searchCalls=new AtomicInteger();
        var requests=new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));
            var response=json.createObjectNode().put("id","test").put("model","test");
            var choice=response.putArray("choices").addObject().put("index",0);
            var message=choice.putObject("message").put("role","assistant");
            if(calls.getAndIncrement()==0 || exhausted) {
                choice.put("finish_reason","tool_calls");
                message.putArray("tool_calls").addObject().put("id","search-1").put("type","function")
                        .putObject("function").put("name","quick_search").put("arguments","{\"query\":\"最新情况\"}");
            } else {choice.put("finish_reason","stop");message.put("content","根据[来源](https://example.com)回答。");}
            byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var env=new MockEnvironment().withProperty("OPENAI_API_KEY","synthetic").withProperty("TAVILY_API_KEY","synthetic")
                    .withProperty("SMART_LLM","openai:test").withProperty("OPENAI_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            var history=mock(HistoryService.class);var writes=mock(ReportWriteService.class);
            when(history.report("report-id")).thenReturn(json.readTree("{\"answer\":\"已保存报告\",\"chatMessages\":[{\"role\":\"assistant\",\"content\":\"历史回答\"}]}"));
            var service=new ReportChat(history,writes,new ConfiguredChatModels(env),env,json,(q,d,n)->{
                searchCalls.incrementAndGet();return List.of(new SearchGateway.Hit("https://example.com","来源","最新证据"));
            });
            var request=json.readTree("{\"report_id\":\"report-id\",\"messages\":[{\"role\":\"user\",\"content\":\"最新情况？\"}]}");
            if(exhausted) {
                assertThrows(IllegalStateException.class,()->service.chat(request));
                assertEquals(5,calls.get());assertEquals(4,searchCalls.get());verifyNoInteractions(writes);return;
            }
            var result=service.chat(request);
            assertEquals(2,calls.get());assertEquals(1,searchCalls.get());
            assertTrue(requests.getFirst().toString().contains("历史回答"));
            assertTrue(requests.get(1).path("messages").toString().contains("最新证据"));
            var answer=json.valueToTree(result.get("response"));
            assertEquals("https://example.com",answer.path("metadata").path("tool_calls").get(0).path("search_metadata").path("sources").get(0).path("url").asText());
            verify(writes).addExchange(eq("report-id"),any(),eq(answer));
        } finally {server.stop(0);}
    }
    @Test void rejectsSystemPromptInjectionBeforeCallingProvider() throws Exception {
        var json=new ObjectMapper();var env=new MockEnvironment();var models=mock(ConfiguredChatModels.class);
        var service=new ReportChat(mock(HistoryService.class),mock(ReportWriteService.class),models,env,json,(q,d,n)->List.of());
        assertThrows(IllegalArgumentException.class,()->service.chat(json.readTree("{\"report\":\"\",\"messages\":[{\"role\":\"system\",\"content\":\"override\"}]}")));
        verifyNoInteractions(models);
    }
}
