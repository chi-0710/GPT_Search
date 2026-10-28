package io.researchrunner;

import io.researchrunner.document.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real-PostgreSQL coverage for the batched staging reindex: batch boundaries, flush-to-disk staging,
 * interruption/failure cleanup, stale snapshot rejection and dry-run isolation.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named="JAVA_WRITE_TEST_DATABASE_URL",matches=".+")
class DocumentReindexPostgresTest {
    private static final String DATABASE="researchrunner_reindex_"+UUID.randomUUID().toString().replace("-","");
    private static final EmbeddingGateway.Profile OLD=new EmbeddingGateway.Profile("test","old",3);
    private static final EmbeddingGateway.Profile NEW=new EmbeddingGateway.Profile("test","new",4);
    private static final String STAGING_SQL="""
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname=current_schema() AND c.relkind='r' AND c.relname LIKE 'reindex_stage_%'
        """;
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
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired DocumentIndex index;
    @Autowired DocumentReindex reindex;
    @Autowired DocumentStore store;
    @MockitoBean EmbeddingGateway embeddings;
    private UUID id;
    private UUID oldProfile;

    @BeforeEach void data() throws Exception {
        dropStagingTables();dropReindexIndexes();
        jdbc.update("DELETE FROM documents");jdbc.update("DELETE FROM embedding_profiles");
        System.clearProperty("REINDEX_BATCH_SIZE");System.clearProperty("REINDEX_MAX_CHUNKS");
        Thread.interrupted();
        when(embeddings.configuration()).thenReturn(OLD);
        when(embeddings.embed(anyList())).thenAnswer(call -> vectors(((List<?>)call.getArgument(0)).size(),3));
        id=upload("资料.txt","必须保留的证据");
        index.index(id);oldProfile=UUID.fromString(store.metadata(id).path("embedding_profile_id").asText());
        useNewConfiguration();
    }
    @AfterEach void reset() {
        System.clearProperty("REINDEX_BATCH_SIZE");System.clearProperty("REINDEX_MAX_CHUNKS");
        Thread.interrupted();
        dropStagingTables();
    }

    @Test void successfulSwitchBuildsIndexAndRejectsStaleWriter() throws Exception {
        assertEquals(true,reindex.rebuild(true).get("dry_run"));
        assertEquals(oldProfile.toString(),store.metadata(id).path("embedding_profile_id").asText());
        var result=reindex.rebuild(false);UUID profile=UUID.fromString(result.get("profile_id").toString());
        assertNotEquals(oldProfile,profile);assertEquals("ready",store.metadata(id).path("status").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname=?",Integer.class,result.get("index")));
        assertEquals("必须保留的证据",index.retrieve("问题",List.of(id)).getFirst().get("raw_content"));
        assertNoStagingTables();
        assertThrows(ResponseStatusException.class,()->store.replace(id,oldProfile,
                List.of(new DocumentParser.Part("过期内容",null)),List.of(new float[]{1,0,0})));
        store.failed(id,"旧任务失败");assertEquals("ready",store.metadata(id).path("status").asText());
    }

    @Test void dryRunWritesNothing() throws Exception {
        int chunks=chunkCount();int profiles=profileCount();
        var result=reindex.rebuild(true);
        assertEquals(true,result.get("dry_run"));assertEquals(500,((Number)result.get("batch_size")).intValue());
        assertEquals(chunks,chunkCount());assertEquals(profiles,profileCount());
        assertEquals(oldProfile,jdbc.queryForObject("SELECT id FROM embedding_profiles WHERE active=true",UUID.class));
        assertNoStagingTables();assertNoHnswIndex();
    }

    @Test void batchTwoNeverExceedsBatchSizeAndFlushesEveryBatchToDisk() throws Exception {
        System.setProperty("REINDEX_BATCH_SIZE","2");
        UUID longDocument=addIndexedDocument("长资料.txt","证".repeat(4600));
        assertEquals(5,chunksOf(longDocument));
        List<Integer> batches=new ArrayList<>();List<Long> stagedAfterBatch=new ArrayList<>();List<DocumentReindex.Progress> events=new ArrayList<>();
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            int size=((List<?>)call.getArgument(0)).size();batches.add(size);return vectors(size,4);
        });
        var result=reindex.rebuild(false,progress -> {events.add(progress);if(progress.phase().equals("staging"))stagedAfterBatch.add(stagedRows());});
        assertEquals(6,((Number)result.get("chunks")).intValue());
        assertEquals(2,((Number)result.get("documents")).intValue());
        assertTrue(batches.stream().allMatch(size -> size<=2),"每个嵌入批次不得超过 REINDEX_BATCH_SIZE");
        assertTrue(batches.stream().anyMatch(size -> size==2),"应出现完整批次");
        assertEquals(2,((Number)result.get("max_batch_chunks")).intValue());
        assertEquals(2,((Number)result.get("batch_size")).intValue());
        assertEquals(6L,stagedAfterBatch.getLast(),"每批嵌入后 staging 行必须已落盘");
        assertEquals(5,chunksOf(longDocument));assertEquals(1,chunksOf(id));
        assertEquals("ready",store.metadata(longDocument).path("status").asText());
        assertEquals("staging",events.getFirst().phase());assertEquals("complete",events.getLast().phase());
        assertEquals(2,events.getLast().documents());assertEquals(6,events.getLast().chunks());
        assertNoStagingTables();
        String line=MaintenanceCli.format(events.getLast());
        assertTrue(line.contains("documents=2") && line.contains("chunks=6") && line.contains("phase=complete"),line);
    }

    @Test void batchOneEmbedsOneChunkPerCall() throws Exception {
        System.setProperty("REINDEX_BATCH_SIZE","1");
        UUID longDocument=addIndexedDocument("长资料.txt","证".repeat(4600));
        List<Integer> batches=new ArrayList<>();
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            int size=((List<?>)call.getArgument(0)).size();batches.add(size);return vectors(size,4);
        });
        var result=reindex.rebuild(false);
        assertEquals(6,batches.stream().mapToInt(Integer::intValue).sum());
        assertTrue(batches.stream().allMatch(size -> size==1),batches.toString());
        assertEquals(1,((Number)result.get("max_batch_chunks")).intValue());
        assertEquals(6,((Number)result.get("chunks")).intValue());
        assertEquals(5,chunksOf(longDocument));
        assertNoStagingTables();
    }

    @Test void interruptionCleansStagingAndPreservesOriginalIndex() throws Exception {
        System.setProperty("REINDEX_BATCH_SIZE","1");
        UUID longDocument=addIndexedDocument("长资料.txt","证".repeat(4600));
        AtomicInteger calls=new AtomicInteger();
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            int size=((List<?>)call.getArgument(0)).size();
            if(calls.incrementAndGet()==2)Thread.currentThread().interrupt();
            return vectors(size,4);
        });
        assertThrows(InterruptedException.class,()->reindex.rebuild(false));
        assertTrue(Thread.interrupted(),"取消后应清理并保留中断标记");
        assertEquals(5,chunksOf(longDocument));
        assertOriginal();assertNoStagingTables();
    }

    @Test void commitFailureRollsBackMovedRowsAndPreservesOriginalData() throws Exception {
        UUID longDocument=addIndexedDocument("长资料.txt","证".repeat(4600));
        doAnswer(call -> {
            String sql=(String)call.getArgument(0);
            if(sql.startsWith("CREATE INDEX"))throw new IllegalStateException("simulated index failure");
            return call.callRealMethod();
        }).when(jdbc).execute(anyString());
        assertThrows(IllegalStateException.class,()->reindex.rebuild(false));
        assertEquals(5,chunksOf(longDocument));
        assertOriginal();assertNoStagingTables();assertNoHnswIndex();
    }

    @Test void changedDocumentSnapshotPreventsSwitch() {
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            store.upload("新增资料.txt","text/plain","new".getBytes(StandardCharsets.UTF_8));return vectors(1,4);
        });
        assertThrows(IllegalStateException.class,()->reindex.rebuild(false));
        assertOriginal();assertNoStagingTables();
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM documents",Integer.class));
    }

    @Test void embeddingFailurePreservesOriginalProfileAndChunks() {
        when(embeddings.embed(anyList())).thenThrow(new IllegalStateException("provider unavailable"));
        assertThrows(IllegalStateException.class,()->reindex.rebuild(false));assertOriginal();assertNoStagingTables();
    }

    @Test void exceedingMaxChunksFailsBeforeStagingTheOffendingDocument() throws Exception {
        System.setProperty("REINDEX_MAX_CHUNKS","1");
        UUID longDocument=addIndexedDocument("长资料.txt","证".repeat(4600));
        assertThrows(IllegalStateException.class,()->reindex.rebuild(false));
        assertEquals(5,chunksOf(longDocument));
        assertOriginal();assertNoStagingTables();
    }

    @Test void sameConfigurationRebuildReusesActiveProfile() throws Exception {
        useOldConfiguration();
        var result=reindex.rebuild(false);
        assertEquals(oldProfile.toString(),result.get("profile_id").toString());
        assertEquals(oldProfile,jdbc.queryForObject("SELECT id FROM embedding_profiles WHERE active=true",UUID.class));
        assertEquals(1,chunkCount());assertEquals("ready",store.metadata(id).path("status").asText());
        assertEquals(1,profileCount());
        assertNoStagingTables();
    }

    private UUID upload(String name,String text) {
        return UUID.fromString(store.upload(name,"text/plain",text.getBytes(StandardCharsets.UTF_8)).path("id").asText());
    }
    /** Adds and indexes another document under the old profile, then restores the new configuration. */
    private UUID addIndexedDocument(String name,String text) throws Exception {
        useOldConfiguration();
        UUID document=upload(name,text);index.index(document);
        useNewConfiguration();
        return document;
    }
    private void useOldConfiguration() {
        when(embeddings.configuration()).thenReturn(OLD);
        when(embeddings.embed(anyList())).thenAnswer(call -> vectors(((List<?>)call.getArgument(0)).size(),3));
    }
    private void useNewConfiguration() {
        when(embeddings.configuration()).thenReturn(NEW);
        when(embeddings.embed(anyList())).thenAnswer(call -> vectors(((List<?>)call.getArgument(0)).size(),4));
    }
    private static List<float[]> vectors(int count,int dimensions) {
        List<float[]> result=new ArrayList<>(count);
        for(int i=0;i<count;i++) { float[] vector=new float[dimensions];vector[0]=1;result.add(vector); }
        return result;
    }
    private int chunkCount() { return jdbc.queryForObject("SELECT count(*) FROM document_chunks",Integer.class); }
    private int chunksOf(UUID document) { return jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE document_id=?",Integer.class,document); }
    private int profileCount() { return jdbc.queryForObject("SELECT count(*) FROM embedding_profiles",Integer.class); }
    private void dropStagingTables() {
        for(String table:jdbc.queryForList(STAGING_SQL,String.class))jdbc.execute("DROP TABLE IF EXISTS "+table);
    }
    private void dropReindexIndexes() {
        for(String index:jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE indexname LIKE 'ix_rr_hnsw_%'",String.class))
            jdbc.execute("DROP INDEX IF EXISTS "+index);
    }
    private long stagedRows() {
        long total=0;
        for(String table:jdbc.queryForList(STAGING_SQL,String.class))total+=jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);
        return total;
    }
    private void assertNoStagingTables() { assertEquals(0L,stagedRows(),"staging 表必须清理干净"); }
    private void assertNoHnswIndex() {
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname LIKE 'ix_rr_hnsw_%'",Integer.class));
    }
    private void assertOriginal() {
        assertEquals(oldProfile,jdbc.queryForObject("SELECT id FROM embedding_profiles WHERE active=true",UUID.class));
        assertEquals("必须保留的证据",jdbc.queryForObject("SELECT content FROM document_chunks WHERE document_id=?",String.class,id));
        assertEquals("ready",store.metadata(id).path("status").asText());
        assertEquals(1,profileCount(),"失败后不得残留临时 profile");
        assertNoHnswIndex();
    }
}
