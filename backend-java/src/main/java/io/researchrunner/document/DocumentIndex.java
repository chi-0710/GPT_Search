package io.researchrunner.document;

import io.researchrunner.infrastructure.ResourceMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Parsing/model calls execute outside database transactions. */
@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class DocumentIndex {
    private final DocumentStore store;
    private final ResourceMapper resources;
    private final EmbeddingGateway embeddings;
    private final int size,overlap,limit;
    private final double threshold;
    public DocumentIndex(DocumentStore store,ResourceMapper resources,EmbeddingGateway embeddings,
            @Value("${DOCUMENT_CHUNK_SIZE:1000}") int size,@Value("${DOCUMENT_CHUNK_OVERLAP:100}") int overlap,
            @Value("${DOCUMENT_RETRIEVAL_LIMIT:8}") int limit,@Value("${DOCUMENT_SIMILARITY_THRESHOLD:0.35}") double threshold) {
        this.store=store;this.resources=resources;this.embeddings=embeddings;this.size=size;this.overlap=overlap;this.limit=limit;this.threshold=threshold;
    }
    public void index(UUID id) throws Exception {
        try {
            var file=resources.document(id);
            if(file==null) throw new IllegalArgumentException("未找到文档");
            var parser=new DocumentParser();
            var chunks=parser.split(parser.parse(file.filename(),file.content()),size,overlap);
            var config=embeddings.configuration();
            var vectors=embeddings.embed(chunks.stream().map(DocumentParser.Part::content).toList());
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException("文档索引已取消");
            if(vectors.size()!=chunks.size()) throw new IllegalArgumentException("嵌入返回数量不一致");
            for(float[] vector:vectors) DocumentStore.vector(vector,config.dimensions());
            var profile=store.profile(config.provider(),config.model(),config.dimensions());
            store.replace(id,UUID.fromString(profile.path("id").asText()),chunks,vectors);
        } catch(Exception e) {
            store.failed(id,"文档索引失败："+e.getClass().getSimpleName());
            throw e;
        }
    }
    public List<Map<String,Object>> retrieve(String query,List<UUID> frozenIds) {
        if(frozenIds.isEmpty()) return List.of();
        var config=embeddings.configuration();
        var profile=store.profile(config.provider(),config.model(),config.dimensions());
        var vectors=embeddings.embed(List.of(query));
        if(vectors.size()!=1) throw new IllegalArgumentException("查询向量数量无效");
        return store.search(UUID.fromString(profile.path("id").asText()),List.copyOf(frozenIds),vectors.getFirst(),limit,threshold);
    }
}
