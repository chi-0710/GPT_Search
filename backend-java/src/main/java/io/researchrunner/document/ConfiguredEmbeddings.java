package io.researchrunner.document;

import io.researchrunner.research.CompatibleModelFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class ConfiguredEmbeddings implements EmbeddingGateway {
    private final Environment environment;
    public ConfiguredEmbeddings(Environment environment) { this.environment=environment; }
    @Override public Profile configuration() {
        String configured=environment.getProperty("EMBEDDING","openai:text-embedding-3-small");
        int colon=configured.indexOf(':');
        return new Profile(colon<0?"openai":configured.substring(0,colon),colon<0?configured:configured.substring(colon+1),
                environment.getProperty("EMBEDDING_DIMENSION",Integer.class,1536));
    }
    @Override public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        Profile profile=configuration();
        io.researchrunner.research.ProviderCatalog.require(
                io.researchrunner.research.ProviderCatalog.Kind.EMBEDDING,profile.provider());
        var variables=new HashMap<String,String>();
        for (String name:List.of("OPENAI_API_KEY","OPENAI_BASE_URL","DASHSCOPE_API_KEY","DASHSCOPE_BASE_URL",
                "NEBIUS_API_KEY","NEBIUS_BASE_URL","AIMLAPI_API_KEY","MINIMAX_API_KEY","OPENROUTER_API_KEY")) {
            String value=environment.getProperty(name);
            if (value!=null) variables.put(name,value);
        }
        int batch=20;
        String kwargs=environment.getProperty("EMBEDDING_KWARGS","{}");
        try {
            var parsed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(kwargs);
            if (!parsed.isObject()) throw new IllegalArgumentException("EMBEDDING_KWARGS 必须为 JSON 对象");
            var keys=parsed.fieldNames();
            while(keys.hasNext()) {
                String key=keys.next();
                if (!java.util.Set.of("dimensions","chunk_size").contains(key)) throw new IllegalArgumentException("尚未迁移的嵌入参数："+key);
            }
            if (parsed.has("dimensions") && parsed.get("dimensions").asInt()!=profile.dimensions()) throw new IllegalArgumentException("嵌入配置维度不一致");
            batch=parsed.path("chunk_size").asInt(20);
        } catch (java.io.IOException e) { throw new IllegalArgumentException("EMBEDDING_KWARGS 必须是有效的 JSON",e); }
        if (batch<1) throw new IllegalArgumentException("嵌入批次必须为正数");
        var model=new CompatibleModelFactory().embedding(CompatibleModelFactory.settings(profile.provider(),profile.model(),variables),profile.dimensions());
        var result=new ArrayList<float[]>();
        for(int start=0;start<texts.size();start+=batch) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var vectors=model.embed(texts.subList(start,Math.min(texts.size(),start+batch)));
            if (vectors.size()!=Math.min(batch,texts.size()-start)) throw new IllegalArgumentException("嵌入返回数量不一致");
            for(float[] vector:vectors) DocumentStore.vector(vector,profile.dimensions());
            result.addAll(vectors);
        }
        return result;
    }
}
