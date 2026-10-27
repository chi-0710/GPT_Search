package io.researchrunner.api;

import io.researchrunner.document.DocumentJobs;
import io.researchrunner.document.DocumentStore;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class DocumentWriteController {
    private final DocumentStore store;
    private final DocumentJobs jobs;
    private final long maxBytes;
    public DocumentWriteController(DocumentStore store,DocumentJobs jobs,@Value("${MAX_BLOB_SIZE_MB:50}") long megabytes) {
        this.store=store;this.jobs=jobs;this.maxBytes=Math.multiplyExact(megabytes,1024L*1024L);
    }
    private com.fasterxml.jackson.databind.JsonNode upload(MultipartFile file) throws java.io.IOException {
        if(file.getSize()>maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"上传的文件过大");
        byte[] bytes;
        try(var input=file.getInputStream()) {
            bytes=input.readNBytes((int)Math.min(Integer.MAX_VALUE,maxBytes+1));
        }
        var result=store.upload(file.getOriginalFilename()==null?"document.bin":file.getOriginalFilename(),file.getContentType(),bytes);
        if(!result.path("reused").asBoolean()) jobs.submit(UUID.fromString(result.path("id").asText()));
        return result;
    }
    @PostMapping("/api/documents")
    public Map<String,Object> create(@RequestParam("file") MultipartFile file) throws java.io.IOException { return Map.of("document",upload(file)); }
    @PostMapping("/upload/")
    public Map<String,Object> legacyUpload(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        var result=upload(file);
        return Map.of("filename",result.path("filename").asText(),"id",result.path("id").asText(),"status",result.path("status").asText());
    }
    @DeleteMapping("/api/documents/{id}")
    public Map<String,Object> delete(@PathVariable String id) {store.delete(parse(id));return Map.of("success",true);}
    @PostMapping("/api/documents/{id}/reindex")
    public Map<String,Object> reindex(@PathVariable String id) {
        UUID uuid=parse(id);jobs.submit(uuid);
        return Map.of("success",true,"status","processing");
    }
    @DeleteMapping("/files/{filename}")
    public void legacyDelete(@PathVariable String filename) {
        throw new ResponseStatusException(HttpStatus.GONE,"请使用 /api/documents/{document_id} 按文档 ID 删除；同名文件可能并存");
    }
    private UUID parse(String id) {
        try{return UUID.fromString(id);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到文档");}
    }
}
