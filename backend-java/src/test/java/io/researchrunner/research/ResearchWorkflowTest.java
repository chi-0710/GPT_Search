package io.researchrunner.research;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

class ResearchWorkflowTest {
    private Map<String, AsyncNodeAction<AgentState>> actions(List<String> visited) {
        Map<String, AsyncNodeAction<AgentState>> nodes = new HashMap<>();
        for (String name : ResearchWorkflow.NODES) nodes.put(name, node_async(state -> {
            visited.add(name);
            return Map.of();
        }));
        nodes.put("human", node_async(state -> Map.of("plan_accepted", true)));
        nodes.put("fact_checker", node_async(state -> Map.of("facts_accepted", true)));
        return nodes;
    }

    @Test
    void compiledGraphCallsSpringModelAndRepairsChaptersBeforeRewriting() throws Exception {
        var visited = new ArrayList<String>();
        var nodes = actions(visited);
        var model = mock(ChatModel.class);
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage("中文草稿 [E1]"))));
        when(model.call(any(Prompt.class))).thenReturn(response);
        var gateway = new SpringModelGateway(model);
        nodes.put("writer", node_async(state -> {
            visited.add("writer");
            return Map.of("report", gateway.call(new Prompt("基于证据写作")).getResult().getOutput().getText());
        }));
        var attempts = new AtomicInteger();
        nodes.put("fact_checker", node_async(state -> Map.of("facts_accepted", attempts.incrementAndGet() > 1,
                "fact_check_revision_count", 1)));
        var result = ResearchWorkflow.compile(nodes, 2, 2).invoke(GraphInput.args(Map.of("query", "主题")), RunnableConfig.empty()).orElseThrow();
        assertEquals("中文草稿 [E1]", result.data().get("report"));
        assertTrue(visited.indexOf("repair_sections") > visited.indexOf("writer"));
        assertTrue(visited.lastIndexOf("writer") > visited.indexOf("repair_sections"));
        assertEquals("publisher", visited.getLast());
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void rejectsUnresolvedFeedbackWithoutPublishing() throws Exception {
        var visited = new ArrayList<String>();
        var nodes = actions(visited);
        nodes.put("human", node_async(state -> Map.of("plan_accepted", false, "plan_revision_count", 1)));
        var graph = ResearchWorkflow.compile(nodes, 0, 0);
        assertThrows(Exception.class, () -> graph.invoke(GraphInput.noArgs(), RunnableConfig.empty()));
        assertFalse(visited.contains("publisher"));
    }

    @Test
    void humanFutureSuspendsTheCompiledGraph() throws Exception {
        var feedback = new CompletableFuture<Map<String, Object>>();
        var entered = new CountDownLatch(1);
        var nodes = actions(new java.util.concurrent.CopyOnWriteArrayList<>());
        nodes.put("human", state -> { entered.countDown(); return feedback; });
        var graph = ResearchWorkflow.compile(nodes, 1, 1);
        var result = CompletableFuture.supplyAsync(() -> graph.invoke(GraphInput.noArgs(), RunnableConfig.empty()));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            feedback.complete(Map.of("plan_accepted", true));
            assertTrue(result.get(5, TimeUnit.SECONDS).isPresent());
        } finally {
            feedback.cancel(true);
            result.cancel(true);
        }
    }

    @Test
    void sectionFailureCancelsAndDrainsSibling() {
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        assertThrows(Exception.class, () -> SectionTasks.gather(List.of(
            () -> { started.await(); throw new IllegalStateException("failed"); },
            () -> { started.countDown(); try { Thread.sleep(30000); return "unexpected"; }
                    finally { stopped.countDown(); } }
        ), 2, Duration.ofSeconds(5)));
        assertEquals(0, stopped.getCount());
    }

    @Test
    void sectionOrderIsIndependentOfCompletionOrder() throws Exception {
        assertEquals(List.of("first", "second"), SectionTasks.gather(List.of(
                () -> { Thread.sleep(50); return "first"; }, () -> "second"), 2, Duration.ofSeconds(5)));
    }

    @Test
    void cancellingModelStreamPropagatesToProviderPublisher() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        var subscription = new SpringModelGateway(model).stream(new Prompt("主题")).subscribe();
        subscription.dispose();
        assertTrue(cancelled.get());
    }

    @Test
    void cancellingCompiledGraphWhileWaitingForHumanDoesNotPublish() throws Exception {
        var entered = new CountDownLatch(1);
        var feedback = new CompletableFuture<Map<String, Object>>();
        var visited = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var nodes = actions(visited);
        nodes.put("human", state -> { entered.countDown(); return feedback; });
        var stream = ResearchWorkflow.compile(nodes, 1, 1).stream(GraphInput.noArgs(), RunnableConfig.empty());
        var execution = stream.forEachAsync(output -> { });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(stream.cancel(true));
            feedback.cancel(true);
            try { execution.get(5, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException expected) { }
            assertTrue(stream.isCancelled());
            assertFalse(visited.contains("publisher"));
        } finally {
            feedback.cancel(true);
            execution.cancel(true);
        }
    }
}
