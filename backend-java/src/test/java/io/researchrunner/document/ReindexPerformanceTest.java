package io.researchrunner.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.researchrunner.JavaMigrations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Environment-gated 1k/5k/10k chunk reindex benchmark against real PostgreSQL + pgvector with a local
 * deterministic embedding stub. It is never part of the default {@code mvn test} path: it only runs when
 * {@code REINDEX_BENCHMARK=1} and the isolated {@code JAVA_WRITE_TEST_DATABASE_URL} are set (see
 * {@code scripts/reindex_benchmark.ps1}).
 *
 * <p>The benchmark writes one JSON report per scale under {@code backend-java/target/}; the script
 * merges those reports and adds the measured surefire-JVM peak working set (RSS).
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named="REINDEX_BENCHMARK",matches="1")
@EnabledIfEnvironmentVariable(named="JAVA_WRITE_TEST_DATABASE_URL",matches=".+")
class ReindexPerformanceTest {
    private static final String DATABASE="researchrunner_reindex_bench_"+UUID.randomUUID().toString().replace("-","");
    private static final long MB=1024L*1024L;
    private static final String OLD_CHUNK_INSERT="INSERT INTO document_chunks(document_id,embedding_profile_id,chunk_index,content,metadata,embedding) "
            +"VALUES(?,?,?,?,CAST(? AS jsonb),CAST(? AS vector))";
    private static DriverManagerDataSource admin;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) throws Exception {
        String url=System.getenv("JAVA_WRITE_TEST_DATABASE_URL");
        if(url==null || !url.endsWith("/researchrunner_java_write_test"))throw new IllegalStateException("Only isolated test database allowed");
        admin=new DriverManagerDataSource(url,"java_test","java_test");new JdbcTemplate(admin).execute("CREATE DATABASE "+DATABASE);
        String target=url.replace("/researchrunner_java_write_test","/"+DATABASE);
        JavaMigrations.migrate(new DriverManagerDataSource(target,"java_test","java_test"),false);
        registry.add("spring.datasource.url",()->target);registry.add("spring.datasource.username",()->"java_test");registry.add("spring.datasource.password",()->"java_test");
        registry.add("spring.datasource.hikari.read-only",()->false);registry.add("researchrunner.writes-enabled",()->true);
    }
    @AfterAll static void cleanup(@Autowired DataSource source) {
        if(source instanceof com.zaxxer.hikari.HikariDataSource pool)pool.close();
        if(admin!=null)new JdbcTemplate(admin).execute("DROP DATABASE "+DATABASE);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentReindex reindex;
    @Autowired ObjectMapper json;
    @MockitoBean EmbeddingGateway embeddings;

    @Test void reindexMemoryStaysBatchedAtScale() throws Exception {
        int targetChunks=Integer.parseInt(System.getenv().getOrDefault("REINDEX_BENCH_CHUNKS","1000"));
        int dimensions=Integer.parseInt(System.getenv().getOrDefault("REINDEX_BENCH_DIMENSIONS","1536"));
        int chunksPerDocument=Integer.parseInt(System.getenv().getOrDefault("REINDEX_BENCH_CHUNKS_PER_DOC","1000"));
        int chunkSize=Integer.parseInt(System.getenv().getOrDefault("DOCUMENT_CHUNK_SIZE","1000"));
        int overlap=Integer.parseInt(System.getenv().getOrDefault("DOCUMENT_CHUNK_OVERLAP","100"));
        assertTrue(targetChunks>0 && dimensions>0 && chunksPerDocument>0);
        assertEquals(1000,chunkSize,"基准固定使用默认分块大小");
        assertEquals(100,overlap,"基准固定使用默认 overlap");
        int documentCount=Math.max(1,targetChunks/chunksPerDocument);
        int chunks=documentCount*chunksPerDocument;
        // Realistic old index: each document has chunksPerDocument old chunks under a distinct active profile.
        jdbc.update("DELETE FROM document_chunks");jdbc.update("DELETE FROM documents");jdbc.update("DELETE FROM embedding_profiles");
        UUID oldProfile=UUID.randomUUID();
        jdbc.update("INSERT INTO embedding_profiles(id,provider,model,dimensions,active,created_at,updated_at) VALUES(?,?,?,?,true,now(),now())",
                oldProfile,"bench","old",dimensions);
        String oldVector=vectorString(dimensions);
        String oldChunkText="旧证据".repeat(Math.max(1,chunkSize-overlap));
        // Paragraphs of exactly 100 code points (98 chars + "\n\n") align with size=1000/overlap=100, so the
        // recursive splitter yields exactly chunksPerDocument chunks without the char-level transient blowup.
        int contentLength=(chunkSize-overlap)*chunksPerDocument+overlap;
        int paragraphLength=100;
        assertEquals(0,contentLength%paragraphLength);
        String paragraph="证".repeat(paragraphLength-2)+"\n\n";
        StringBuilder text=new StringBuilder(contentLength);
        for(int i=0;i<contentLength/paragraphLength;i++)text.append(paragraph);
        byte[] content=text.toString().getBytes(StandardCharsets.UTF_8);
        for(int d=0;d<documentCount;d++) {
            UUID document=UUID.randomUUID();String filename="bench-"+d+".txt";
            jdbc.update("INSERT INTO documents(id,filename,mime_type,byte_size,sha256,content,status,error,embedding_profile_id,created_at,updated_at) "
                    +"VALUES(?,?,?,?,?,?,'ready',null,?,now(),now())",document,filename,"text/plain",content.length,
                    DocumentStore.hash(content),content,oldProfile);
            List<Object[]> oldChunks=new ArrayList<>(Math.min(chunksPerDocument,500));
            for(int index=0;index<chunksPerDocument;index++) {
                oldChunks.add(new Object[]{document,oldProfile,index,oldChunkText,"{\"filename\":\""+filename+"\"}",oldVector});
                if(oldChunks.size()==500) { jdbc.batchUpdate(OLD_CHUNK_INSERT,oldChunks);oldChunks.clear(); }
            }
            if(!oldChunks.isEmpty())jdbc.batchUpdate(OLD_CHUNK_INSERT,oldChunks);
        }
        assertEquals(chunks,jdbc.queryForObject("SELECT count(*) FROM document_chunks",Integer.class));
        when(embeddings.configuration()).thenReturn(new EmbeddingGateway.Profile("bench","new",dimensions));
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            List<?> texts=call.getArgument(0);
            List<float[]> vectors=new ArrayList<>(texts.size());
            for(int i=0;i<texts.size();i++) { float[] vector=new float[dimensions];vector[0]=1;vectors.add(vector); }
            return vectors;
        });
        Runtime runtime=Runtime.getRuntime();
        System.gc();Thread.sleep(50);System.gc();
        long heapBefore=usedHeap(runtime);
        AtomicLong heapPeak=new AtomicLong(heapBefore);
        AtomicBoolean sampling=new AtomicBoolean(true);
        Thread sampler=new Thread(() -> {
            while(sampling.get()) {
                heapPeak.accumulateAndGet(usedHeap(runtime),Math::max);
                try { Thread.sleep(25); } catch(InterruptedException stopped) { return; }
            }
        },"reindex-benchmark-heap-sampler");
        sampler.setDaemon(true);
        AtomicLong commitStart=new AtomicLong(0);
        AtomicLong liveHeapPeak=new AtomicLong(heapBefore);
        AtomicInteger sampledDocuments=new AtomicInteger(0);
        long started=System.nanoTime();
        sampler.start();
        var result=reindex.rebuild(false,progress -> {
            if(progress.phase().equals("verifying")) { commitStart.set(System.nanoTime());return; }
            // Once per document, force a GC and record the retained (live) heap: this is the number that must stay
            // flat as the library grows, because only the current batch is kept in Java memory.
            if(progress.phase().equals("staging") && progress.documents()>sampledDocuments.get()) {
                sampledDocuments.set(progress.documents());
                System.gc();
                liveHeapPeak.accumulateAndGet(usedHeap(runtime),Math::max);
            }
        });
        long ended=System.nanoTime();
        sampling.set(false);sampler.interrupt();sampler.join(2000);
        long heapAfter=usedHeap(runtime);
        long elapsed=(ended-started)/1_000_000L;
        int stagedChunks=((Number)result.get("chunks")).intValue();
        int batchSize=((Number)result.get("batch_size")).intValue();
        int maxBatchChunks=((Number)result.get("max_batch_chunks")).intValue();
        assertEquals(chunks,stagedChunks,"所有 chunk 必须进入 staging 并完成切换");
        assertTrue(batchSize>=1);
        assertTrue(maxBatchChunks<=batchSize,"单个嵌入/staging 批次不得超过 REINDEX_BATCH_SIZE");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname=?",Integer.class,result.get("index")));
        assertNoStagingTables();
        long stagingMs=commitStart.get()==0?elapsed:(commitStart.get()-started)/1_000_000L;
        long commitMillis=commitStart.get()==0?0:(ended-commitStart.get())/1_000_000L;
        long heapPeakMb=heapPeak.get()/MB;
        long heapBeforeMb=heapBefore/MB;
        long naiveVectorMb=(long)chunks*dimensions*4/MB;
        ObjectNode report=json.createObjectNode();
        report.put("generatedAt",java.time.OffsetDateTime.now().toString());
        report.put("scaleChunks",chunks);
        report.put("documents",documentCount);
        report.put("chunksPerDocument",chunksPerDocument);
        report.put("dimensions",dimensions);
        report.put("batchSize",batchSize);
        report.put("stagingMs",stagingMs);
        report.put("commitMs",commitMillis);
        report.put("totalMs",elapsed);
        report.put("embedCalls",((Number)result.get("embed_calls")).intValue());
        report.put("maxBatchChunks",maxBatchChunks);
        report.put("stagedChunks",stagedChunks);
        report.put("heapBeforeMb",heapBeforeMb);
        report.put("heapPeakMb",heapPeakMb);
        report.put("heapPeakDeltaMb",Math.max(0,heapPeakMb-heapBeforeMb));
        report.put("liveHeapPeakMb",liveHeapPeak.get()/MB);
        report.put("liveHeapPeakDeltaMb",Math.max(0,liveHeapPeak.get()/MB-heapBeforeMb));
        report.put("heapAfterMb",heapAfter/MB);
        report.put("heapMaxMb",runtime.maxMemory()/MB);
        report.put("naiveWholeLibraryVectorMb",naiveVectorMb);
        report.put("boundedMemory",maxBatchChunks<=batchSize);
        report.put("jvmVersion",System.getProperty("java.version"));
        report.put("jvmArgs",String.join(" ",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()));
        String target=System.getenv().getOrDefault("REINDEX_BENCH_OUTPUT","target/reindex-benchmark-"+chunks+".json");
        Path path=Path.of(target);
        if(path.getParent()!=null)Files.createDirectories(path.getParent());
        Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+System.lineSeparator(),StandardCharsets.UTF_8);
        System.out.println("[reindex-benchmark] "+json.writeValueAsString(report));
    }

    private static long usedHeap(Runtime runtime) { return runtime.totalMemory()-runtime.freeMemory(); }
    private static long millis(long startNanos) { return (System.nanoTime()-startNanos)/1_000_000L; }
    private static String vectorString(int dimensions) {
        StringBuilder value=new StringBuilder(dimensions*4).append('[');
        for(int i=0;i<dimensions;i++) { if(i>0)value.append(',');value.append(i==0?"0.5":"0"); }
        return value.append(']').toString();
    }
    private void assertNoStagingTables() {
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname=current_schema() AND c.relkind='r' AND c.relname LIKE 'reindex_stage_%'
            """,Integer.class));
    }
}
