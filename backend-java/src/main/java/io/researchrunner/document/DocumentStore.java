package io.researchrunner.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.researchrunner.infrastructure.DocumentWriteMapper;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
@Transactional
public class DocumentStore {
    private final DocumentWriteMapper mapper;
    private final ObjectMapper json;
    private final long maxBytes;
    public DocumentStore(DocumentWriteMapper mapper,ObjectMapper json,@Value("${MAX_BLOB_SIZE_MB:50}") long maxMegabytes) {
        this.mapper=mapper; this.json=json; this.maxBytes=Math.multiplyExact(maxMegabytes,1024L*1024L);
    }

    public JsonNode upload(String filename,String mime,byte[] content) {
        String name=safeName(filename);
        if (name.isBlank()) throw new IllegalArgumentException("A filename is required");
        if (content.length>maxBytes) throw new ResponseStatusException(HttpStatus.REQUEST_ENTITY_TOO_LARGE,"上传文件过大");
        String hash=hash(content);
        String inserted=mapper.insert(UUID.randomUUID(),name,mime==null?"application/octet-stream":mime,content.length,hash,content);
        ObjectNode result=(ObjectNode)parse(mapper.byDigest(name,hash));
        result.put("reused",inserted==null);
        return result;
    }

    public JsonNode metadata(UUID id) { return parse(mapper.metadata(id)); }
    public void delete(UUID id) {
        if (mapper.delete(id)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到文档");
    }
    public void processing(UUID id) {
        if (mapper.status(id,"processing",null)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到文档");
    }
    public void failed(UUID id,String error) { mapper.status(id,"failed",error.substring(0,Math.min(error.length(),10000))); }

    public JsonNode profile(String provider,String model,int dimensions) {
        if (dimensions<1) throw new IllegalArgumentException("向量维度必须为正数");
        mapper.profileLock();
        String active=mapper.activeProfile();
        if (active==null) return parse(mapper.createProfile(UUID.randomUUID(),provider,model,dimensions));
        JsonNode result=parse(active);
        if (!provider.equals(result.path("provider").asText()) || !model.equals(result.path("model").asText())
                || dimensions!=result.path("dimensions").asInt()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Configured embedding model differs from the active database profile; run reindex first");
        }
        return result;
    }

    public void replace(UUID document,UUID profile,List<DocumentParser.Part> chunks,List<float[]> embeddings) {
        JsonNode configuration=parse(mapper.profile(profile));
        int dimensions=configuration.path("dimensions").asInt();
        if (chunks.isEmpty() || chunks.size()!=embeddings.size()) throw new IllegalArgumentException("Chunk and embedding counts differ or are empty");
        List<String> vectors=embeddings.stream().map(vector -> vector(vector,dimensions)).toList();
        if (mapper.lock(document)==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到文档");
        String name=parse(mapper.metadata(document)).path("filename").asText();
        mapper.clearChunks(document);
        for (int index=0;index<chunks.size();index++) {
            var metadata=json.createObjectNode().put("filename",name);
            if (chunks.get(index).page()!=null) metadata.put("page_number",chunks.get(index).page());
            mapper.chunk(document,profile,index,chunks.get(index).content(),metadata.toString(),vectors.get(index));
        }
        mapper.ready(document,profile);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> search(UUID profile,List<UUID> ids,float[] embedding,int limit,double threshold) {
        if (ids.isEmpty()) return List.of();
        if (limit<1 || !Double.isFinite(threshold)) throw new IllegalArgumentException("检索参数无效");
        int dimensions=parse(mapper.profile(profile)).path("dimensions").asInt();
        String vector=vector(embedding,dimensions);
        return mapper.search(profile,ids.stream().map(UUID::toString).toList(),vector,Math.min(limit,100),threshold).stream()
                .map(value -> json.<Map<String,Object>>convertValue(parse(value),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {})).toList();
    }

    private JsonNode parse(String value) {
        if (value==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到文档或向量配置");
        try { return json.readTree(value); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public static String vector(float[] values,int dimensions) {
        if (values.length!=dimensions) throw new IllegalArgumentException("Embedding dimension must be "+dimensions);
        double norm=0;
        for (float value:values) { if (!Float.isFinite(value)) throw new IllegalArgumentException("向量包含非有限值"); norm+=value*value; }
        if (norm==0) throw new IllegalArgumentException("不能检索零向量");
        return Arrays.toString(values);
    }
    public static String safeName(String filename) {
        String value=filename==null?"":filename.replace('\\','/');
        value=value.substring(value.lastIndexOf('/')+1).replace("\u0000","");
        return value.codePointCount(0,value.length())>255?value.substring(0,value.offsetByCodePoints(0,255)):value;
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
