package io.researchrunner.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.researchrunner.infrastructure.ResourceMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded-memory document reindex.
 *
 * <p>The previous implementation kept every parsed chunk and embedding of the whole library in a
 * Java list before replacing rows in one large transaction, so a big library could exhaust the JVM
 * heap. This version streams the preparation phase:
 *
 * <ol>
 *   <li>an <em>inactive</em> temporary embedding profile is created (or an existing inactive profile
 *       with the same provider/model/dimensions is reused);</li>
 *   <li>documents are parsed in {@code REINDEX_BATCH_SIZE} (default 500) chunk batches, embedded and
 *       written to an unlogged {@code reindex_stage_*} table, flushing each batch, so the Java heap
 *       only ever holds one batch;</li>
 *   <li>a single transaction locks {@code documents}, verifies that the document snapshot and the
 *       active profile did not change, deletes the old chunks, moves the staged rows into
 *       {@code document_chunks}, activates the temporary profile, updates the documents, builds the
 *       HNSW index and verifies its validity and row count;</li>
 *   <li>on any failure or interruption the staging table and unused temporary profile are dropped and
 *       the transaction rolls back, so the old profile, old chunks and document states stay intact.</li>
 * </ol>
 *
 * <p>Cancel semantics: interrupting the rebuild thread is observed between embedding/staging batches
 * and before the commit; a best-effort shutdown hook drops the staging table if the JVM exits while a
 * rebuild is running. Progress events are exposed through {@link ProgressListener} for the maintenance
 * CLI.
 */
@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class DocumentReindex {
    /** One observable step of a rebuild; printed by the maintenance CLI. */
    public record Progress(String phase,int documents,long chunks,long elapsedMillis,String detail) { }
    @FunctionalInterface public interface ProgressListener { void onProgress(Progress progress); }

    private static final String STAGING_PREFIX="reindex_stage_";
    private static final Pattern STAGING_NAME=Pattern.compile("reindex_stage_[0-9]+_[0-9a-f]{8}");
    /** Staging tables left behind by a killed JVM are dropped by a later run once they are this old. */
    private static final long STALE_STAGING_MILLIS=2L*60*60*1000;

    private final JdbcTemplate jdbc;
    private final ResourceMapper resources;
    private final EmbeddingGateway embeddings;
    private final Environment environment;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final int size,overlap;

    public DocumentReindex(JdbcTemplate jdbc,ResourceMapper resources,EmbeddingGateway embeddings,Environment environment,
            PlatformTransactionManager manager,ObjectMapper json,@Value("${DOCUMENT_CHUNK_SIZE:1000}") int size,
            @Value("${DOCUMENT_CHUNK_OVERLAP:100}") int overlap) {
        this.jdbc=jdbc;this.resources=resources;this.embeddings=embeddings;this.environment=environment;this.json=json;
        this.transaction=new TransactionTemplate(manager);this.size=size;this.overlap=overlap;
    }

    public Map<String,Object> rebuild(boolean dryRun) throws Exception {
        return rebuild(dryRun,progress -> {});
    }

    public Map<String,Object> rebuild(boolean dryRun,ProgressListener listener) throws Exception {
        long started=System.nanoTime();
        var target=embeddings.configuration();
        if(target.dimensions()<1 || target.dimensions()>2000)throw new IllegalArgumentException("HNSW 向量维度必须在 1 至 2000 之间");
        int batchSize=batchSize();
        int maxChunks=maxChunks();
        var before=snapshot();
        var active=jdbc.queryForList("SELECT id FROM embedding_profiles WHERE active=true");
        if(dryRun) {
            var result=new LinkedHashMap<String,Object>();
            result.put("documents",before.size());result.put("provider",target.provider());result.put("model",target.model());
            result.put("dimensions",target.dimensions());result.put("batch_size",batchSize);result.put("max_chunks",maxChunks);
            result.put("dry_run",true);
            return result;
        }
        cleanupStaleStagingTables();
        UUID profile=stagingProfile(target);
        String staging=STAGING_PREFIX+System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8);
        var activeStaging=new AtomicReference<>(staging);
        var shutdownHook=new Thread(() -> dropStagingTableQuietly(activeStaging.get()),"reindex-staging-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        boolean committed=false;
        try {
            createStagingTable(staging);
            var parser=new DocumentParser();
            long chunkTotal=0;int documentCount=0;int embedCalls=0;int maxBatchChunks=0;
            for(var row:before) {
                failIfInterrupted();
                UUID document=(UUID)row.get("id");
                var file=resources.document(document);
                if(file==null || !file.sha256().equals(row.get("sha256")))throw new IllegalStateException("准备期间资料已变化，请重试");
                var chunks=parser.split(parser.parse(file.filename(),file.content()),size,overlap);
                if(chunks.isEmpty())throw new IllegalArgumentException("资料没有可索引正文");
                if(chunkTotal+chunks.size()>maxChunks)
                    throw new IllegalStateException("待重建分块超过 REINDEX_MAX_CHUNKS="+maxChunks+"，请分批或提高上限");
                for(int start=0;start<chunks.size();start+=batchSize) {
                    failIfInterrupted();
                    var batch=chunks.subList(start,Math.min(chunks.size(),start+batchSize));
                    var texts=batch.stream().map(DocumentParser.Part::content).toList();
                    var vectors=embeddings.embed(texts);
                    embedCalls++;maxBatchChunks=Math.max(maxBatchChunks,texts.size());
                    if(vectors.size()!=texts.size())throw new IllegalArgumentException("嵌入数量不匹配");
                    stageBatch(staging,document,start,batch,vectors,file.filename(),target.dimensions());
                    chunkTotal+=batch.size();
                    listener.onProgress(new Progress("staging",documentCount+1,chunkTotal,elapsedMillis(started),
                            "batch="+texts.size()+" document="+file.filename()));
                }
                documentCount++;
            }
            failIfInterrupted();
            listener.onProgress(new Progress("verifying",documentCount,chunkTotal,elapsedMillis(started),"校验快照并原子切换"));
            final int documents=documentCount;final long chunks=chunkTotal;
            final int calls=embedCalls;final int widestBatch=maxBatchChunks;
            var result=transaction.execute(status -> commitStaging(staging,profile,target,before,active,documents,chunks));
            committed=true;
            var output=new LinkedHashMap<String,Object>(result);
            output.put("batch_size",batchSize);output.put("max_chunks",maxChunks);
            output.put("embed_calls",calls);output.put("max_batch_chunks",widestBatch);
            output.put("elapsed_ms",elapsedMillis(started));output.put("dry_run",false);
            listener.onProgress(new Progress("complete",documents,chunks,elapsedMillis(started),"profile="+profile));
            return output;
        } finally {
            activeStaging.set(null);
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch(IllegalStateException shutdownInProgress) { /* JVM already shutting down; the hook handles cleanup */ }
            dropStagingTable(staging);
            if(!committed)dropProfileIfUnused(profile);
        }
    }

    private List<Map<String,Object>> snapshot() {
        return jdbc.queryForList("SELECT id,sha256,status,embedding_profile_id,updated_at FROM documents ORDER BY id");
    }
    private int batchSize() { return Math.max(1,environment.getProperty("REINDEX_BATCH_SIZE",Integer.class,500)); }
    private int maxChunks() { return Math.max(1,environment.getProperty("REINDEX_MAX_CHUNKS",Integer.class,200000)); }
    private static long elapsedMillis(long startNanos) { return (System.nanoTime()-startNanos)/1_000_000L; }
    private static void failIfInterrupted() throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("批量重建已取消");
    }

    /** Reuses an inactive profile with the same configuration or creates one; never reactivates it. */
    private UUID stagingProfile(EmbeddingGateway.Profile target) {
        UUID profile=transaction.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('researchrunner.embedding_profile',0))");
            return jdbc.queryForObject("""
                INSERT INTO embedding_profiles(id,provider,model,dimensions,active,created_at,updated_at)
                VALUES(?,?,?,?,false,now(),now()) ON CONFLICT(provider,model,dimensions)
                DO UPDATE SET updated_at=now() RETURNING id
                """,UUID.class,UUID.randomUUID(),target.provider(),target.model(),target.dimensions());
        });
        if(profile==null)throw new IllegalStateException("无法准备临时向量配置");
        return profile;
    }

    private void createStagingTable(String staging) {
        jdbc.execute("CREATE UNLOGGED TABLE "+staging+" (document_id uuid NOT NULL,chunk_index integer NOT NULL,"
                +"content text NOT NULL,metadata jsonb NOT NULL,embedding vector NOT NULL,"
                +"PRIMARY KEY(document_id,chunk_index))");
    }

    /**
     * One batch of already-embedded chunks: vectors are stringified per batch and inserted with a JDBC
     * batch, so neither the chunk list nor the vectors outlive the batch.
     */
    private void stageBatch(String staging,UUID document,int start,List<DocumentParser.Part> chunks,List<float[]> vectors,
            String filename,int dimensions) {
        if(chunks.size()!=vectors.size())throw new IllegalArgumentException("嵌入数量不匹配");
        List<Object[]> rows=new ArrayList<>(chunks.size());
        for(int offset=0;offset<chunks.size();offset++) {
            DocumentParser.Part part=chunks.get(offset);
            ObjectNode metadata=json.createObjectNode().put("filename",filename);
            if(part.page()!=null)metadata.put("page_number",part.page());
            rows.add(new Object[]{document,start+offset,part.content(),metadata.toString(),
                    DocumentStore.vector(vectors.get(offset),dimensions)});
        }
        jdbc.batchUpdate("INSERT INTO "+staging+"(document_id,chunk_index,content,metadata,embedding) "
                +"VALUES(?,?,?,CAST(? AS jsonb),CAST(? AS vector))",rows);
    }

    /** Atomically swaps the staged rows in; any exception rolls the whole swap back and keeps the old index. */
    private Map<String,Object> commitStaging(String staging,UUID profile,EmbeddingGateway.Profile target,
            List<Map<String,Object>> before,List<Map<String,Object>> active,int documents,long chunks) {
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('researchrunner.embedding_profile',0))");
        jdbc.execute("LOCK TABLE documents IN SHARE ROW EXCLUSIVE MODE");
        if(!before.equals(snapshot()) || !active.equals(jdbc.queryForList("SELECT id FROM embedding_profiles WHERE active=true")))
            throw new IllegalStateException("准备期间资料或向量配置已变化，保留原索引");
        List<UUID> ids=before.stream().map(row -> (UUID)row.get("id")).toList();
        // uq_document_chunks_index is UNIQUE(document_id,chunk_index), so old rows must leave their
        // slots before the staged rows move in. Both statements are part of the same transaction, so
        // a failure restores the deleted chunks together with everything else.
        jdbc.batchUpdate("DELETE FROM document_chunks WHERE document_id=?",
                ids.stream().map(id -> new Object[]{id}).toList());
        int inserted=jdbc.update("INSERT INTO document_chunks(document_id,embedding_profile_id,chunk_index,content,metadata,embedding) "
                +"SELECT document_id,?,chunk_index,content,metadata,embedding FROM "+staging
                +" ORDER BY document_id,chunk_index",profile);
        jdbc.update("UPDATE embedding_profiles SET active=false,updated_at=now() WHERE active=true");
        jdbc.update("UPDATE embedding_profiles SET active=true,updated_at=now() WHERE id=?",profile);
        jdbc.batchUpdate("UPDATE documents SET embedding_profile_id=?,status='ready',error=null,updated_at=now() WHERE id=?",
                ids.stream().map(id -> new Object[]{profile,id}).toList());
        String index="ix_rr_hnsw_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE INDEX "+index+" ON document_chunks USING hnsw ((embedding::vector("+target.dimensions()+")) vector_cosine_ops) WHERE embedding_profile_id='"+profile+"'::uuid");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT indisvalid AND indisready FROM pg_index WHERE indexrelid=to_regclass(?)",Boolean.class,index)))
            throw new IllegalStateException("新索引未就绪，保留原索引");
        int rows=jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE embedding_profile_id=?",Integer.class,profile);
        if((long)inserted!=chunks || rows!=chunks)throw new IllegalStateException("新索引验证失败，保留原索引");
        var result=new LinkedHashMap<String,Object>();
        result.put("documents",documents);result.put("profile_id",profile.toString());result.put("index",index);result.put("chunks",rows);
        return result;
    }

    private void cleanupStaleStagingTables() {
        List<String> leftovers;
        try {
            leftovers=jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=current_schema() AND c.relkind='r' AND c.relname LIKE 'reindex_stage_%'
                """,String.class);
        } catch(Exception ignored) { return; }
        long now=System.currentTimeMillis();
        for(String table:leftovers) {
            Long created=stagingTimestamp(table);
            if(created==null || now-created<STALE_STAGING_MILLIS)continue;
            dropStagingTable(table);
        }
    }
    private static Long stagingTimestamp(String table) {
        if(!STAGING_NAME.matcher(table).matches())return null;
        String rest=table.substring(STAGING_PREFIX.length());
        try { return Long.parseLong(rest.substring(0,rest.indexOf('_'))); }
        catch(RuntimeException invalid) { return null; }
    }
    private void dropStagingTable(String table) {
        if(table==null)return;
        try { jdbc.execute("DROP TABLE IF EXISTS "+table); }
        catch(Exception ignored) { /* best effort: a hard JVM kill is recovered by the stale-table sweep */ }
    }
    /** Shutdown-hook variant that must not use the application transaction manager. */
    private void dropStagingTableQuietly(String table) {
        if(table==null || !STAGING_NAME.matcher(table).matches())return;
        var source=jdbc.getDataSource();
        if(source==null)return;
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            statement.execute("DROP TABLE IF EXISTS "+table);
        } catch(Exception ignored) { /* shutdown is best effort */ }
    }
    private void dropProfileIfUnused(UUID profile) {
        if(profile==null)return;
        try {
            jdbc.update("""
                DELETE FROM embedding_profiles p WHERE p.id=? AND p.active=false
                AND NOT EXISTS (SELECT 1 FROM document_chunks c WHERE c.embedding_profile_id=p.id)
                AND NOT EXISTS (SELECT 1 FROM documents d WHERE d.embedding_profile_id=p.id)
                """,profile);
        } catch(Exception ignored) { /* best effort: an unused inactive profile is harmless */ }
    }
}
