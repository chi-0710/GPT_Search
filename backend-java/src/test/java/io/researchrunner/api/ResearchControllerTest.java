package io.researchrunner.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.researchrunner.document.*;
import io.researchrunner.infrastructure.HistoryMapper;
import io.researchrunner.research.*;
import io.researchrunner.report.ReportExports;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResearchControllerTest {
    @Test void localResearchExecutesGraphExportsAndCompletesWithFrozenScope() throws Exception {
        var json=new ObjectMapper();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            var body=json.readTree(exchange.getRequestBody());String instruction=body.path("messages").get(0).path("content").asText();
            String output=instruction.contains("规划简体")?"{\"title\":\"研究标题\",\"sections\":[\"章节\"]}":instruction.contains("严格审阅")?"{\"accepted\":true,\"feedback\":\"\"}":"研究正文 [E1]";
            var response=json.createObjectNode().put("id","test").put("model","test");
            var choice=response.putArray("choices").addObject().put("index",0).put("finish_reason","stop");
            choice.putObject("message").put("role","assistant").put("content",output);
            byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var runs=mock(RunStore.class);var exports=mock(ReportExports.class);var documents=mock(DocumentIndex.class);
        var store=mock(DocumentStore.class);var embeddings=mock(EmbeddingGateway.class);var history=mock(HistoryMapper.class);
        UUID id=UUID.randomUUID(),profile=UUID.randomUUID();var run=new RunStore.Identity(UUID.randomUUID(),"test-report");
        when(runs.create(anyString(),anyString(),anyString(),anyString(),anyMap())).thenReturn(run);
        when(runs.complete(eq(run.runId()),any())).thenReturn(true);
        when(embeddings.configuration()).thenReturn(new EmbeddingGateway.Profile("test","test",3));
        when(store.profile("test","test",3)).thenReturn(json.createObjectNode().put("id",profile.toString()));
        when(history.documents()).thenReturn(List.of(json.writeValueAsString(Map.of("id",id.toString(),"status","ready","embedding_profile_id",profile.toString()))));
        when(documents.retrieve(anyString(),eq(List.of(id)))).thenReturn(List.of(Map.of("document_id",id.toString(),"url","资料.txt","title","资料.txt","raw_content","来源证据")));
        when(exports.save(eq(run.researchId()),eq(run.runId()),anyString(),eq(true),eq(true))).thenReturn(Map.of("pdf","/pdf","docx","/docx","md","/md"));
        var env=new MockEnvironment().withProperty("OPENAI_API_KEY","synthetic").withProperty("OPENAI_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
        for(String role:List.of("FAST","SMART","STRATEGIC"))env.withProperty(role+"_LLM","openai:test");
        var controller=new ResearchController(runs,exports,documents,store,embeddings,history,env,json);
        try {
            var result=controller.report(Map.of("task","研究主题","report_source","local","document_scope","selected","document_ids",List.of(id.toString()),"generate_in_background",false));
            assertEquals("/pdf",result.get("pdf_path"));assertTrue(result.get("report").toString().contains("/evidence/1/view"));
            verify(documents,times(2)).retrieve(anyString(),eq(List.of(id)));verify(runs).complete(eq(run.runId()),any());verify(runs,never()).fail(any(),any());
        } finally {controller.close();server.stop(0);}
    }
}
