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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void localResearchExecutesGraphExportsAndCompletesWithFrozenScope(boolean interactive) throws Exception {
        var json=new ObjectMapper();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var instructions=java.util.Collections.synchronizedList(new ArrayList<String>());
        server.createContext("/v1/chat/completions",exchange->{
            var body=json.readTree(exchange.getRequestBody());String instruction=body.path("messages").get(0).path("content").asText();
            instructions.add(instruction);
            String output=instruction.contains("研究编辑")?"{\"title\":\"研究标题\",\"sections\":[\"章节\"]}"
                    :instruction.contains("事实核查智能体")?"{\"accepted\":true,\"feedback\":\"\"}"
                    :instruction.contains("研究文章审阅者")?"{\"accepted\":true,\"feedback\":\"\"}"
                    :instruction.contains("负责根据审阅意见修订草稿")?"{\"draft\":\"研究正文 [E1]\",\"revision_notes\":\"已补充证据\"}"
                    :instruction.contains("数据可视化智能体")?"None":"研究正文 [E1]";
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
        when(exports.saveWithWarnings(eq(run.researchId()),eq(run.runId()),anyString(),eq(true),eq(true))).thenReturn(new ReportExports.Saved(Map.of("pdf","/pdf","docx","/docx","md","/md"),List.of()));
        var env=new MockEnvironment().withProperty("OPENAI_API_KEY","synthetic").withProperty("OPENAI_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()+"/v1")
                .withProperty("LLM_KWARGS","{\"temperature\":0.2,\"max_tokens\":1000}");
        for(String role:List.of("FAST","SMART","STRATEGIC"))env.withProperty(role+"_LLM","openai:test");
        var service=new ResearchExecutionService(runs,new ResearchPublication(runs,exports),documents,store,embeddings,history,env,json);
        var controller=new ResearchController(service);
        try {
            var request=new HashMap<String,Object>(Map.of("task","研究主题","report_source","local","document_scope","selected","document_ids",List.of(id.toString()),"generate_in_background",false));
            request.put("headers",Map.of("tavily_api_key","request-override"));
            request.put("include_human_feedback",interactive);request.put("follow_guidelines",true);
            var events=new java.util.concurrent.CopyOnWriteArrayList<Map<String,Object>>();
            var reference=new java.util.concurrent.atomic.AtomicReference<ResearchConnection>();
            var connection=service.connection(event->{
                events.add(event);
                if(event.get("type").equals("human_feedback")) assertTrue(reference.get().feedback(run.runId(),
                        ((Number)event.get("round")).intValue(),event.get("request_id").toString(),"no"));
            });reference.set(connection);
            var result=interactive?service.report(request,connection,true):controller.report(request);
            assertEquals("/pdf",result.get("pdf_path"));assertTrue(result.get("report").toString().contains("/evidence/1/view"));
            assertTrue(instructions.stream().anyMatch(prompt->prompt.contains("你必须在报告末尾以参考文献形式列出所有使用过的来源文档名称")),
                    "local 来源必须使用 initial_research.documents 变体");
            assertTrue(instructions.stream().noneMatch(prompt->prompt.contains("[网站链接](url)")),
                    "local 来源不得使用 web 引用分支");
            @SuppressWarnings("unchecked") var information=(Map<String,Object>)result.get("research_information");
            @SuppressWarnings("unchecked") var costs=(Map<String,Object>)information.get("research_costs");
            assertEquals("unknown",costs.get("status"));assertTrue(costs.containsKey("agents"));assertTrue(costs.containsKey("detail"));
            verify(documents,times(2)).retrieve(anyString(),eq(List.of(id)));verify(runs).complete(eq(run.runId()),any());verify(runs,never()).fail(any(),any());
            if(interactive) {
                assertEquals(1,events.stream().filter(e->e.get("type").equals("human_feedback")).count());
                assertEquals(1,events.stream().filter(e->e.get("type").equals("report_complete")).count());
                assertTrue(events.stream().anyMatch(e->"section_draft".equals(e.get("content"))));
            }
        } finally {service.close();server.stop(0);}
    }
}
