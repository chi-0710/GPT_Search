package io.researchrunner.document;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentJobsTest {
    @Test void duplicateReindexDoesNotResetAnActiveJobAndShutdownWaitsForCancellation() throws Exception {
        var index=mock(DocumentIndex.class);var store=mock(DocumentStore.class);
        UUID id=UUID.randomUUID();var started=new CountDownLatch(1);var stopped=new CountDownLatch(1);
        doAnswer(call->{started.countDown();try{Thread.sleep(30000);}finally{stopped.countDown();}return null;}).when(index).index(id);
        var jobs=new DocumentJobs(index,store);
        try {
            jobs.submit(id);assertTrue(started.await(2,TimeUnit.SECONDS));jobs.submit(id);
            verify(store,times(1)).processing(id);verify(index,times(1)).index(id);
        } finally {jobs.close();}
        assertEquals(0,stopped.getCount());
    }
}
