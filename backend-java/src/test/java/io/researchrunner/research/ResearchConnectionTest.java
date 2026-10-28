package io.researchrunner.research;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchConnectionTest {
    @Test void disconnectInterruptsExternalWorkAndWaitsForCleanup() throws Exception {
        var connection=new ResearchConnection(mock(RunStore.class),event->{});
        connection.create(()->new RunStore.Identity(UUID.randomUUID(),"report"));
        var started=new CountDownLatch(1);var cleaned=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var task=pool.submit(()->connection.call(()->{
                started.countDown();try {new CountDownLatch(1).await();return "unexpected";}
                finally {cleaned.countDown();}
            }));
            assertTrue(started.await(3,TimeUnit.SECONDS));connection.close();
            assertTrue(connection.awaitCleanup(java.time.Duration.ofSeconds(3)));
            assertEquals(0,cleaned.getCount());assertThrows(ExecutionException.class,()->task.get(3,TimeUnit.SECONDS));
        }
    }

    @Test void cancellationSignalStopsAndAwaitsSiblingSections() throws Exception {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var started=new CountDownLatch(2);var cleaned=new CountDownLatch(2);
        Callable<String> chapter=()->{started.countDown();try{new CountDownLatch(1).await();return "unexpected";}finally{cleaned.countDown();}};
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var task=pool.submit(()->SectionTasks.gather(List.of(chapter,chapter),2,java.time.Duration.ofSeconds(3),cancelled::get));
            assertTrue(started.await(3,TimeUnit.SECONDS));cancelled.set(true);
            assertThrows(ExecutionException.class,()->task.get(4,TimeUnit.SECONDS));assertEquals(0,cleaned.getCount());
        }
    }
    @Test void cancellationPreventsPublicationAndFurtherRunCreation() throws Exception {
        var runs=mock(RunStore.class);var publisher=mock(ResearchPublication.class);
        var connection=new ResearchConnection(runs,event->{});
        var run=new RunStore.Identity(UUID.randomUUID(),"report");connection.create(()->run);
        connection.close();connection.close();
        assertThrows(CancellationException.class,()->connection.publish(publisher,null,true,true));
        verify(runs,times(1)).cancel(run.runId());verifyNoInteractions(publisher);
        assertThrows(IllegalStateException.class,()->connection.create(()->{fail("must not create another database run");return run;}));
    }
    @Test void committedPublicationSurvivesTransportFailure() throws Exception {
        var runs=mock(RunStore.class);var publisher=mock(ResearchPublication.class);
        var run=new RunStore.Identity(UUID.randomUUID(),"report");
        var result=new ResearchResult("正文",List.of(),Map.of(),List.of(),List.of());
        when(publisher.publish(run,result,false,false)).thenReturn(Map.of("md","/md"));
        var connection=new ResearchConnection(runs,event->{throw new java.io.IOException("closed");});
        connection.create(()->run);assertEquals("/md",connection.publish(publisher,result,false,false).get("md"));
        connection.close();verify(runs,never()).cancel(any());
    }
    @Test void feedbackRequiresRunRoundAndRequestAndCannotBeReused() throws Exception {
        var runs=mock(RunStore.class);var events=new LinkedBlockingQueue<Map<String,Object>>();
        var connection=new ResearchConnection(runs,events::add);
        var run=new RunStore.Identity(UUID.randomUUID(),"report");connection.create(()->run);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var review=pool.submit(()->connection.human().review("标题",List.of("章节"),0));
            var event=events.poll(3,TimeUnit.SECONDS);assertNotNull(event);
            assertFalse(connection.feedback(run.runId(),0,"stale","no"));
            assertFalse(connection.feedback(UUID.randomUUID(),0,event.get("request_id").toString(),"no"));
            assertTrue(connection.feedback(run.runId(),0,event.get("request_id").toString(),"no"));
            assertTrue(review.get(3,TimeUnit.SECONDS).accepted());
            assertFalse(connection.feedback(run.runId(),0,event.get("request_id").toString(),"no"));
        } finally {connection.close();}
    }
}
