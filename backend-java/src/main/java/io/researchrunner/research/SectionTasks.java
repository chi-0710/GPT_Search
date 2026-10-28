package io.researchrunner.research;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Bounded parallel sections; failure interrupts siblings and waits for termination. */
public final class SectionTasks {
    private SectionTasks() { }

    public static <T> List<T> gather(List<Callable<T>> sections, int concurrency, Duration cleanupTimeout)
            throws Exception {
        return gather(sections,concurrency,cleanupTimeout,()->false);
    }

    public static <T> List<T> gather(List<Callable<T>> sections, int concurrency, Duration cleanupTimeout,
            java.util.function.BooleanSupplier cancelled) throws Exception {
        if (concurrency < 1 || cleanupTimeout.isNegative() || cleanupTimeout.isZero()) {
            throw new IllegalArgumentException("并行数和清理超时必须为正数");
        }
        if (sections.isEmpty()) return List.of();
        var pool = Executors.newFixedThreadPool(Math.min(concurrency, sections.size()),
                Thread.ofVirtual().factory());
        record Indexed<T>(int index, T value) { }
        var completed = new ExecutorCompletionService<Indexed<T>>(pool);
        List<Future<Indexed<T>>> futures = new ArrayList<>();
        boolean interrupted = false;
        try {
            for (int i = 0; i < sections.size(); i++) {
                final int index = i;
                futures.add(completed.submit(() -> new Indexed<>(index, sections.get(index).call())));
            }
            List<T> result = new ArrayList<>(Collections.nCopies(sections.size(), null));
            for (int i = 0; i < sections.size(); i++) {
                Future<Indexed<T>> next;
                do {
                    if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("研究已取消");
                    next=completed.poll(100,TimeUnit.MILLISECONDS);
                } while(next==null);
                var item = next.get();
                result.set(item.index(), item.value());
            }
            return result;
        } catch (InterruptedException exception) {
            interrupted = true;
            throw exception;
        } finally {
            for (var future : futures) future.cancel(true);
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(cleanupTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("章节任务未在清理期限内停止");
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
