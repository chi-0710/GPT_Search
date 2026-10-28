package io.researchrunner;

import com.fasterxml.jackson.databind.JsonNode;
import io.researchrunner.report.ReportWriteService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Full schema and actual HTTP transactions; never connects to the application database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="JAVA_WRITE_TEST_DATABASE_URL", matches=".+")
class ReportWritePostgresTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportWriteService writes;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired io.researchrunner.research.RunStore runs;
    @Autowired io.researchrunner.research.ResearchPublication publication;
    @Autowired io.researchrunner.research.InterruptedTaskRecovery recovery;
    @Autowired io.researchrunner.document.DocumentStore documents;

    @Test void recoveryOnlyFailsJavaRunsAndPreservesDocumentContent() {
        var javaRun=runs.create("interrupted Java","multi_agents","web","Objective",Map.of("execution_backend","java"));
        var pythonRun=runs.create("other runtime","multi_agents","web","Objective",Map.of());
        var doc=documents.upload("recover-"+UUID.randomUUID()+".txt","text/plain","保留资料".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var id=UUID.fromString(doc.path("id").asText());
        recovery.recover();
        assertEquals("failed",read(javaRun.researchId()).path("status").asText());
        assertEquals("running",read(pythonRun.researchId()).path("status").asText());
        assertEquals("failed",documents.metadata(id).path("status").asText());
        assertEquals("保留资料",new String(jdbc.queryForObject("SELECT content FROM documents WHERE id=?",byte[].class,id),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0,recovery.recover().get("reports"));
        runs.fail(pythonRun.runId(),"test cleanup");
    }

    @Test void chatExchangeRollsBackBothMessagesWhenAssistantIsInvalid() throws Exception {
        String report=report();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->writes.addExchange(report,
                json.readTree("{\"role\":\"user\",\"content\":\"问题\"}"),json.readTree("{\"role\":\"invalid\",\"content\":\"回答\"}")));
        assertTrue(read(report).path("chatMessages").isEmpty());
        writes.addExchange(report,json.readTree("{\"role\":\"user\",\"content\":\"问题\"}"),
                json.readTree("{\"role\":\"assistant\",\"content\":\"回答\",\"metadata\":{\"tool_calls\":[{\"tool\":\"quick_search\"}]}}"));
        assertEquals(2,read(report).path("chatMessages").size());
        assertEquals("quick_search",read(report).path("chatMessages").get(1).path("metadata").path("tool_calls").get(0).path("tool").asText());
    }

    @Test void writeEnabledCorsAllowsBrowserUploadPreflight() {
        var headers=new HttpHeaders();headers.setOrigin("http://localhost:3800");headers.setAccessControlRequestMethod(HttpMethod.POST);
        var response=http.exchange("/api/documents",HttpMethod.OPTIONS,new HttpEntity<>(headers),String.class);
        assertEquals(200,response.getStatusCode().value());assertTrue(response.getHeaders().getAccessControlAllowMethods().contains(HttpMethod.POST));
    }
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @Test void realWebSocketHandlesPingAndRejectsInvalidRequests() throws Exception {
        var messages=new java.util.concurrent.LinkedBlockingQueue<String>();
        var listener=new java.net.http.WebSocket.Listener() {
            private final StringBuilder text=new StringBuilder();
            @Override public void onOpen(java.net.http.WebSocket socket){socket.request(1);}
            @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket socket,CharSequence data,boolean last) {
                text.append(data);if(last){messages.add(text.toString());text.setLength(0);}socket.request(1);return null;
            }
        };
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            var socket=client.newWebSocketBuilder().buildAsync(java.net.URI.create("ws://localhost:"+port+"/ws"),listener).get(5,java.util.concurrent.TimeUnit.SECONDS);
            try {
                socket.sendText("ping",true).join();assertEquals("pong",messages.poll(5,java.util.concurrent.TimeUnit.SECONDS));
                socket.sendText("start {\"task\":\"\"}",true).join();
                assertEquals("error",json.readTree(messages.poll(5,java.util.concurrent.TimeUnit.SECONDS)).path("type").asText());
                socket.sendText("{\"type\":\"human_feedback\",\"content\":\"no\"}",true).join();
                assertEquals("error",json.readTree(messages.poll(5,java.util.concurrent.TimeUnit.SECONDS)).path("type").asText());
            } finally {socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE,"test complete").join();}
        }
    }

    @Test void publicationRollsBackArtifactsEventsAndEvidenceOnCompletionFailure() {
        var run=runs.create("publication rollback","multi_agents","web","Objective",Map.of());
        var duplicate=Map.<String,Object>of("number",1,"content","evidence");
        var result=new io.researchrunner.research.ResearchResult("正文",java.util.List.of(),Map.of(),
                java.util.List.of(duplicate,duplicate),java.util.List.of());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->publication.publish(run,result,false,false));
        for(String table:java.util.List.of("artifacts","research_events","research_evidence")) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE run_id=?",Integer.class,run.runId()));
        }
        assertEquals("running",read(run.researchId()).path("status").asText());
        runs.fail(run.runId(),"test cleanup");
    }

    @Test void cancelledRunCannotCreatePublishedArtifacts() {
        var run=runs.create("cancel publication","multi_agents","web","Objective",Map.of());
        assertTrue(runs.cancel(run.runId()));
        var result=new io.researchrunner.research.ResearchResult("正文",java.util.List.of(),Map.of(),
                java.util.List.of(Map.of("number",1,"content","evidence")),java.util.List.of());
        assertThrows(IllegalStateException.class,()->publication.publish(run,result,false,false));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE run_id=?",Integer.class,run.runId()));
        assertEquals("failed",read(run.researchId()).path("status").asText());
        assertEquals("cancelled",jdbc.queryForObject("SELECT status FROM research_runs WHERE id=?",String.class,run.runId()));
    }

    @Test
    void runCompletionPersistsEvidenceAndPreventsTerminalStateOverwrite() {
        var identity = runs.create("中文研究", "deep", "hybrid", "Objective",
                Map.of("api_key", "synthetic-secret", "max_tokens", 100, "headers", Map.of("Authorization", "synthetic")));
        runs.append(identity.runId(), Map.of("type", "logs", "content", "starting_research", "output", "开始"));
        var result = new io.researchrunner.research.ResearchResult("# 报告 [E1]",
                java.util.List.of(Map.of("url", "https://example.com", "content", "来源")),
                Map.of("total", 0.1), java.util.List.of(Map.of("number", 1, "content", "来源", "source_type", "web")), java.util.List.of());
        assertTrue(runs.complete(identity.runId(), result));
        assertFalse(runs.complete(identity.runId(), result));
        assertFalse(runs.fail(identity.runId(), "late cancellation"));
        assertThrows(IllegalStateException.class, () -> runs.append(identity.runId(), Map.of("type", "logs")));
        assertEquals("completed", read(identity.researchId()).path("status").asText());
        assertEquals(1, read(identity.researchId()).path("orderedData").size());
        String options = jdbc.queryForObject("SELECT request_options::text FROM research_runs WHERE id=?", String.class, identity.runId());
        assertFalse(options.contains("synthetic"));
        assertTrue(options.contains("100"));
        assertEquals("multi_agents", jdbc.queryForObject("SELECT report_type FROM research_runs WHERE id=?", String.class, identity.runId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM research_evidence WHERE run_id=?", Integer.class, identity.runId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM research_sources WHERE run_id=?", Integer.class, identity.runId()));
    }

    @Test
    void duplicateEvidenceRollsBackCompletionAndFailureKeepsProgress() {
        var identity = runs.create("topic", null, "web", "Objective", Map.of());
        runs.append(identity.runId(), Map.of("type", "logs", "output", "progress"));
        Map<String, Object> evidence = Map.of("number", 1, "content", "evidence");
        var result = new io.researchrunner.research.ResearchResult("body", java.util.List.of(), Map.of(),
                java.util.List.of(evidence, evidence), java.util.List.of());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> runs.complete(identity.runId(), result));
        assertEquals("running", read(identity.researchId()).path("status").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM research_evidence WHERE run_id=?", Integer.class, identity.runId()));
        assertTrue(runs.fail(identity.runId(), "failed"));
        assertEquals("failed", read(identity.researchId()).path("status").asText());
        assertEquals(1, read(identity.researchId()).path("orderedData").size());
    }

    @Test
    void directServiceTransactionUsesRealDatabase() throws Exception {
        String id = "direct-" + UUID.randomUUID();
        assertEquals(id, writes.upsert(json.readTree("{\"id\":\"" + id + "\",\"answer\":\"正文\"}")));
        writes.addMessage(id, json.readTree("{\"content\":\"真实数据库消息\"}"));
        assertEquals(id, writes.upsert(json.readTree("{\"id\":\"" + id + "\",\"answer\":\"新正文\"}")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getenv("JAVA_WRITE_TEST_DATABASE_URL");
        if (url == null || !url.endsWith("/researchrunner_java_write_test")) {
            throw new IllegalArgumentException("Only the isolated write test database is allowed");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "java_test");
        registry.add("spring.datasource.password", () -> "java_test");
        registry.add("spring.datasource.hikari.read-only", () -> false);
        registry.add("researchrunner.writes-enabled", () -> true);
    }

    private String report() {
        String id = "write-test-" + UUID.randomUUID();
        var response = http.postForEntity("/api/reports", Map.of("id", id, "question", "中文问题", "answer", "正文"), JsonNode.class);
        assertEquals(200, response.getStatusCode().value());
        return id;
    }

    private JsonNode read(String id) {
        return http.getForObject("/api/reports/" + id, JsonNode.class).get("report");
    }

    @Test
    void reportAndChatRoundTripPreservesHistoryAndDeletionCascades() {
        String id = report();
        assertEquals("completed", read(id).path("status").asText());
        String messageId = UUID.randomUUID().toString();
        var input = Map.of("id", messageId, "role", "user", "content", "来源在哪里？", "timestamp", 100);
        assertEquals(200, http.postForEntity("/api/reports/" + id + "/chat", input, JsonNode.class).getStatusCode().value());
        assertEquals(200, http.postForEntity("/api/reports/" + id + "/chat", input, JsonNode.class).getStatusCode().value());
        var messages = read(id).path("chatMessages");
        assertEquals(1, messages.size());
        assertEquals(100, messages.get(0).path("timestamp").asLong());
        assertEquals(messageId, messages.get(0).path("id").asText());
        http.put("/api/reports/" + id, Map.of("answer", "新正文"));
        assertEquals("新正文", read(id).path("answer").asText());
        assertEquals(1, read(id).path("chatMessages").size());
        http.delete("/api/reports/" + id);
        assertEquals(404, http.getForEntity("/api/reports/" + id, JsonNode.class).getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE id=?::uuid", Integer.class, messageId));
    }

    @Test
    void invalidChatReplacementRollsBackWholeReportWrite() {
        String id = report();
        var input = Map.of("id", id, "answer", "must rollback", "chatMessages",
                java.util.List.of(Map.of("role", "invalid", "content", "bad")));
        assertEquals(409, http.postForEntity("/api/reports", input, JsonNode.class).getStatusCode().value());
        assertEquals("正文", read(id).path("answer").asText());
    }

    @Test
    void rejectsChatIdentityOwnedByAnotherReport() {
        String first = report();
        String second = report();
        var message = Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "message");
        assertEquals(200, http.postForEntity("/api/reports/" + first + "/chat", message, JsonNode.class).getStatusCode().value());
        assertEquals(409, http.postForEntity("/api/reports/" + second + "/chat", message, JsonNode.class).getStatusCode().value());
        assertTrue(read(second).path("chatMessages").isEmpty());
    }

    @Test
    void concurrentMessagesHaveUniqueOrderedSequences() throws Exception {
        String id = report();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++) {
                final int number = i;
                calls.add(executor.submit(() -> http.postForEntity("/api/reports/" + id + "/chat",
                        Map.of("role", "user", "content", "消息" + number), JsonNode.class).getStatusCode().value()));
            }
            for (var call : calls) assertEquals(200, call.get());
        }
        assertEquals(8, read(id).path("chatMessages").size());
        assertEquals(8, jdbc.queryForObject("SELECT count(DISTINCT sequence) FROM chat_messages c JOIN reports r ON r.id=c.report_id WHERE r.public_id=?", Integer.class, id));
    }

    @Test
    void oldClientTimestampDoesNotMoveReportBackwards() {
        String id = report();
        long before = read(id).path("timestamp").asLong();
        assertEquals(200, http.postForEntity("/api/reports", Map.of("id", id, "timestamp", 1, "question", "changed"), JsonNode.class).getStatusCode().value());
        assertEquals(before, read(id).path("timestamp").asLong());
        assertEquals("changed", read(id).path("question").asText());
    }
}
