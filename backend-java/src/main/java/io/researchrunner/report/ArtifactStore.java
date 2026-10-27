package io.researchrunner.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.document.DocumentStore;
import io.researchrunner.infrastructure.ArtifactWriteMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
@Transactional
public class ArtifactStore {
    private final ArtifactWriteMapper mapper;
    private final ObjectMapper json;
    private final long limit;
    public ArtifactStore(ArtifactWriteMapper mapper,ObjectMapper json,@Value("${MAX_BLOB_SIZE_MB:50}") long megabytes) {
        this.mapper=mapper;this.json=json;this.limit=Math.multiplyExact(megabytes,1024L*1024L);
    }
    public JsonNode save(byte[] content,String filename,String kind,String mime,String report,UUID run,String legacy,Map<String,Object> metadata) {
        if(content.length>limit) throw new IllegalArgumentException("附件超过大小限制");
        String name=DocumentStore.safeName(filename);
        if(name.isEmpty()) name="artifact.bin";
        String path=legacy==null||legacy.isEmpty()?null:legacy.replace('\\','/').strip().replaceAll("^/+","").replaceAll("/+","/");
        if(path!=null) path=path.replaceAll("(^|/)\\./","$1").replaceAll("/$","");
        String result=mapper.save(UUID.randomUUID(),report,run,kind,name,mime==null?"application/octet-stream":mime,
                content.length,DocumentStore.hash(content),content,path,json.valueToTree(metadata==null?Map.of():metadata).toString());
        try{return json.readTree(result);}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
}
