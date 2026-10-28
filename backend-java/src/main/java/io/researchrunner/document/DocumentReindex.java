package io.researchrunner.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.infrastructure.ResourceMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Prepare all embeddings first, then atomically replace chunks, build HNSW and activate the profile. */
@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class DocumentReindex {
    private final JdbcTemplate jdbc;
    private final ResourceMapper resources;
    private final EmbeddingGateway embeddings;
    private final DocumentStore store;
    private final TransactionTemplate transaction;
    private final int size,overlap;
    private record Prepared(UUID id,List<DocumentParser.Part> chunks,List<float[]> vectors) {}
    public DocumentReindex(JdbcTemplate jdbc,ResourceMapper resources,EmbeddingGateway embeddings,DocumentStore store,
            PlatformTransactionManager manager,@Value("${DOCUMENT_CHUNK_SIZE:1000}") int size,@Value("${DOCUMENT_CHUNK_OVERLAP:100}") int overlap) {
        this.jdbc=jdbc;this.resources=resources;this.embeddings=embeddings;this.store=store;
        this.transaction=new TransactionTemplate(manager);this.size=size;this.overlap=overlap;
    }
    private List<Map<String,Object>> snapshot() {
        return jdbc.queryForList("SELECT id,sha256,status,embedding_profile_id,updated_at FROM documents ORDER BY id");
    }
    public Map<String,Object> rebuild(boolean dryRun) throws Exception {
        var target=embeddings.configuration();
        if(target.dimensions()<1 || target.dimensions()>2000)throw new IllegalArgumentException("HNSW 向量维度必须在 1 至 2000 之间");
        var before=snapshot();
        var active=jdbc.queryForList("SELECT id FROM embedding_profiles WHERE active=true");
        if(dryRun)return Map.of("documents",before.size(),"provider",target.provider(),"model",target.model(),"dimensions",target.dimensions(),"dry_run",true);
        List<Prepared> prepared=new ArrayList<>();var parser=new DocumentParser();
        for(var row:before) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("批量重建已取消");
            UUID id=(UUID)row.get("id");var file=resources.document(id);
            if(file==null || !file.sha256().equals(row.get("sha256")))throw new IllegalStateException("准备期间资料已变化，请重试");
            var chunks=parser.split(parser.parse(file.filename(),file.content()),size,overlap);
            if(chunks.isEmpty())throw new IllegalArgumentException("资料没有可索引正文");
            var vectors=embeddings.embed(chunks.stream().map(DocumentParser.Part::content).toList());
            if(vectors.size()!=chunks.size())throw new IllegalArgumentException("嵌入数量不匹配");
            for(var vector:vectors)DocumentStore.vector(vector,target.dimensions());
            prepared.add(new Prepared(id,chunks,vectors));
        }
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("批量重建已取消");
        return transaction.execute(status->{
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('researchrunner.embedding_profile',0))");
            jdbc.execute("LOCK TABLE documents IN SHARE ROW EXCLUSIVE MODE");
            if(!before.equals(snapshot()) || !active.equals(jdbc.queryForList("SELECT id FROM embedding_profiles WHERE active=true")))
                throw new IllegalStateException("准备期间资料或向量配置已变化，保留原索引");
            UUID profile=jdbc.queryForObject("""
                INSERT INTO embedding_profiles(id,provider,model,dimensions,active,created_at,updated_at)
                VALUES(?,?,?,?,false,now(),now()) ON CONFLICT(provider,model,dimensions)
                DO UPDATE SET updated_at=now() RETURNING id
                """,UUID.class,UUID.randomUUID(),target.provider(),target.model(),target.dimensions());
            jdbc.update("UPDATE embedding_profiles SET active=false,updated_at=now() WHERE active=true");
            jdbc.update("UPDATE embedding_profiles SET active=true,updated_at=now() WHERE id=?",profile);
            for(var document:prepared)store.replace(document.id(),profile,document.chunks(),document.vectors());
            String index="ix_rr_hnsw_"+UUID.randomUUID().toString().replace("-","");
            jdbc.execute("CREATE INDEX "+index+" ON document_chunks USING hnsw ((embedding::vector("+target.dimensions()+")) vector_cosine_ops) WHERE embedding_profile_id='"+profile+"'::uuid");
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT indisvalid AND indisready FROM pg_index WHERE indexrelid=to_regclass(?)",Boolean.class,index)))
                throw new IllegalStateException("新索引未就绪，保留原索引");
            int rows=jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE embedding_profile_id=?",Integer.class,profile);
            if(rows!=prepared.stream().mapToInt(p->p.chunks().size()).sum())throw new IllegalStateException("新索引验证失败，保留原索引");
            return Map.<String,Object>of("documents",prepared.size(),"profile_id",profile.toString(),"index",index,"chunks",rows,"dry_run",false);
        });
    }
}
