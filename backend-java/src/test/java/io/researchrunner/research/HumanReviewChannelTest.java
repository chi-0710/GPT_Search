package io.researchrunner.research;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HumanReviewChannelTest {
    @Test void feedbackMustMatchRunAndRoundAndNoMustBeExact() throws Exception {
        UUID run=UUID.randomUUID();var entered=new LinkedBlockingQueue<HumanReviewChannel.Request>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor();var channel=new HumanReviewChannel(run,entered::add,Duration.ofSeconds(3))) {
            var result=pool.submit(()->channel.review("标题",List.of("章节"),0));
            assertNotNull(entered.poll(2,TimeUnit.SECONDS));
            assertFalse(channel.submit(UUID.randomUUID(),0,"no"));assertFalse(channel.submit(run,1,"no"));
            assertTrue(channel.submit(run,0,"not enough evidence"));assertFalse(result.get(2,TimeUnit.SECONDS).accepted());
            var next=pool.submit(()->channel.review("标题",List.of("章节"),1));
            assertNotNull(entered.poll(2,TimeUnit.SECONDS));assertFalse(channel.submit(run,0,"no"));
            assertTrue(channel.submit(run,1," NO "));assertTrue(next.get(2,TimeUnit.SECONDS).accepted());
        }
    }
    @Test void disconnectCancelsPendingReviewWithoutApproval() throws Exception {
        var entered=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor();var channel=new HumanReviewChannel(UUID.randomUUID(),r->entered.countDown(),Duration.ofSeconds(30))) {
            var result=pool.submit(()->channel.review("标题",List.of("章节"),0));
            assertTrue(entered.await(2,TimeUnit.SECONDS));channel.close();
            var error=assertThrows(ExecutionException.class,()->result.get(2,TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class,error.getCause());
        }
    }
}
