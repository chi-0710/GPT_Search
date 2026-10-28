package io.researchrunner.research;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** One instance per connection/run. Feedback from another run or an old round cannot accept a plan. */
public final class HumanReviewChannel implements ResearchNodes.Human,AutoCloseable {
    public record Request(UUID runId,int round,String title,List<String> sections) { }
    @FunctionalInterface public interface Sender {void send(Request request) throws Exception;}
    private record Pending(int round,CompletableFuture<ResearchNodes.Decision> decision) { }
    private final UUID runId;
    private final Sender sender;
    private final Duration timeout;
    private Pending pending;
    private boolean closed;
    public HumanReviewChannel(UUID runId,Sender sender,Duration timeout) {
        if(timeout.isZero() || timeout.isNegative())throw new IllegalArgumentException("人工审核等待期限无效");
        this.runId=runId;this.sender=sender;this.timeout=timeout;
    }
    @Override public ResearchNodes.Decision review(String title,List<String> sections,int round) throws Exception {
        Pending active;
        synchronized(this) {
            if(closed)throw new CancellationException("研究已取消");
            if(pending!=null)throw new IllegalStateException("已有待处理的人工审核");
            active=new Pending(round,new CompletableFuture<>());pending=active;
        }
        try {
            sender.send(new Request(runId,round,title,List.copyOf(sections)));
            return active.decision().get(timeout.toMillis(),TimeUnit.MILLISECONDS);
        } finally {
            synchronized(this) {if(pending==active)pending=null;}
            active.decision().cancel(true);
        }
    }
    private static final java.util.Set<String> NO_FEEDBACK = java.util.Set.of(
            "no", "无", "没有", "没有反馈", "不需要", "无需修改");

    /** Python treats these exact answers as "accept the plan"; other text requests a revision. */
    static boolean noFeedback(String feedback) {
        return feedback != null && NO_FEEDBACK.contains(feedback.strip().toLowerCase(java.util.Locale.ROOT));
    }

    public synchronized boolean submit(UUID run,int round,String feedback) {
        if(closed || pending==null || !runId.equals(run) || pending.round()!=round || feedback==null || feedback.isBlank())return false;
        return pending.decision().complete(new ResearchNodes.Decision(noFeedback(feedback),feedback));
    }

    /**
     * Legacy clients (and the pre-run_id protocol) send only {@code content}. The feedback is
     * bound to this connection's current pending review, so it still cannot accept another run.
     */
    public synchronized boolean submitCurrent(String feedback) {
        if(closed || pending==null || feedback==null || feedback.isBlank())return false;
        return pending.decision().complete(new ResearchNodes.Decision(noFeedback(feedback),feedback));
    }
    @Override public synchronized void close() {
        closed=true;if(pending!=null)pending.decision().cancel(true);
    }
}
