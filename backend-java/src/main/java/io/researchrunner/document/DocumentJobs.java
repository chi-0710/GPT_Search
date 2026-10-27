package io.researchrunner.document;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class DocumentJobs {
    private final DocumentIndex index;
    private final DocumentStore store;
    private final ConcurrentMap<UUID,Boolean> pending=new ConcurrentHashMap<>();
    private final ExecutorService executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),Thread.ofVirtual().factory(),new ThreadPoolExecutor.AbortPolicy());
    public DocumentJobs(DocumentIndex index,DocumentStore store) {this.index=index;this.store=store;}
    public synchronized void submit(UUID id) {
        if(pending.putIfAbsent(id,true)!=null) return;
        try {
            store.processing(id);
            executor.submit(() -> {
                try {index.index(id);} catch(Exception ignored) { /* Index records sanitized failure in the database. */ }
                finally {synchronized(DocumentJobs.this) {pending.remove(id);}}
            });
        } catch(RejectedExecutionException e) {
            pending.remove(id);store.failed(id,"索引队列已满，请稍后重试");
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"索引队列已满，请稍后重试");
        } catch(RuntimeException e) {
            pending.remove(id);
            throw e;
        }
    }
    @PreDestroy public void close() throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(10,TimeUnit.SECONDS)) throw new IllegalStateException("文档索引任务未及时停止");
        for (UUID id:pending.keySet()) store.failed(id,"服务停止，文档索引已中断，请重新索引");
        pending.clear();
    }
}
