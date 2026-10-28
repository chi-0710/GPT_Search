package io.researchrunner.research;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;

/** Run-scoped cancellation and publication lock; never serialized into graph state. */
public final class ResearchConnection implements AutoCloseable {
    @FunctionalInterface public interface Events {void send(Map<String,Object> event) throws Exception;}
    private final Events events;
    private final RunStore runs;
    private RunStore.Identity run;
    private HumanReviewChannel human;
    private Future<?> task;
    private boolean cancelled;
    private boolean finished;
    private final Map<Thread,Integer> workers=new java.util.HashMap<>();
    public ResearchConnection(RunStore runs,Events events) {this.runs=runs;this.events=events;}
    public synchronized RunStore.Identity create(java.util.function.Supplier<RunStore.Identity> factory) {
        if(run!=null)throw new IllegalStateException("连接已有研究任务");
        if(cancelled)throw new CancellationException();
        run=factory.get();
        human=new HumanReviewChannel(run.runId(),request->emit(Map.of("type","human_feedback","content","request",
                "run_id",request.runId(),"round",request.round(),"request_id",request.runId()+":"+request.round(),
                "output",request.title()+"\n"+String.join("\n",request.sections()))),Duration.ofMinutes(30));
        return run;
    }
    public synchronized void task(Future<?> task) {this.task=task;if(cancelled)task.cancel(true);}
    public synchronized boolean cancelled() {return cancelled;}
    public synchronized HumanReviewChannel human() {return human;}
    public <T> T call(java.util.concurrent.Callable<T> action) throws Exception {
        Thread thread=Thread.currentThread();
        synchronized(this) {if(cancelled)throw new CancellationException();workers.merge(thread,1,Integer::sum);}
        try {
            T result=action.call();
            synchronized(this) {if(cancelled)throw new CancellationException();}
            return result;
        } finally {
            synchronized(this) {workers.computeIfPresent(thread,(key,count)->count==1?null:count-1);notifyAll();}
        }
    }
    public synchronized boolean feedback(UUID run,int round,String requestId,String feedback) {
        return !cancelled && !finished && (run+":"+round).equals(requestId)
                && human!=null && human.submit(run,round,feedback);
    }
    public void emit(Map<String,Object> event) throws Exception {
        synchronized(this) {if(cancelled)throw new CancellationException();}
        runs.append(run.runId(),event);
        events.send(event);
    }
    public synchronized Map<String,String> publish(ResearchPublication publisher,ResearchResult result,boolean pdf,boolean docx) throws Exception {
        if(cancelled)throw new CancellationException();
        var paths=publisher.publish(run,result,pdf,docx);
        finished=true;
        // Publication is committed before transport delivery. A delivery failure cannot undo it.
        try {events.send(Map.of("type","report_complete","output",result.report()));events.send(Map.of("type","path","output",paths));}
        catch(Exception ignored) { /* Report remains available in history after disconnect. */ }
        return paths;
    }
    public synchronized void failed() {finished=true;if(human!=null)human.close();}
    public synchronized boolean awaitCleanup(Duration timeout) throws InterruptedException {
        long deadline=System.nanoTime()+timeout.toNanos();
        while(!workers.isEmpty()) {
            long remaining=deadline-System.nanoTime();if(remaining<=0)return false;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this,remaining);
        }
        return true;
    }
    @Override public synchronized void close() {
        if(finished || cancelled)return;
        cancelled=true;
        if(human!=null)human.close();
        if(task!=null)task.cancel(true);
        for(Thread worker:workers.keySet())worker.interrupt();
        if(run!=null)runs.cancel(run.runId());
    }
}
