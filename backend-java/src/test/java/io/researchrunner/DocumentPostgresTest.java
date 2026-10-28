package io.researchrunner;

import com.fasterxml.jackson.databind.JsonNode;
import io.researchrunner.document.*;
import io.researchrunner.report.ArtifactStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="JAVA_WRITE_TEST_DATABASE_URL",matches=".+")
class DocumentPostgresTest {
    @Autowired io.researchrunner.report.ReportExports exports;
    @Autowired io.researchrunner.research.RunStore runs;
    @Test void exportsPersistRealFilesAndReuseArtifactIdentity() throws Exception {
        var run=runs.create("导出测试","multi_agents","web","Objective",Map.of());
        var first=exports.save(run.researchId(),run.runId(),"# 中文报告\n\n证据内容",true,true);
        var second=exports.save(run.researchId(),run.runId(),"# 中文报告\n\n更新内容",true,true);
        assertEquals(first,second);
        byte[] pdf=http.getForObject(second.get("pdf"),byte[].class);
        try(var document=org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertTrue(java.text.Normalizer.normalize(new org.apache.pdfbox.text.PDFTextStripper().getText(document),java.text.Normalizer.Form.NFKC).contains("更新内容"));
        }
    }
    @Autowired DocumentStore store;
    @Autowired DocumentIndex index;
    @Autowired ArtifactStore artifacts;
    @Autowired TestRestTemplate http;
    @MockitoBean EmbeddingGateway embeddings;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        String url=System.getenv("JAVA_WRITE_TEST_DATABASE_URL");
        if(url==null||!url.endsWith("/researchrunner_java_write_test"))throw new IllegalArgumentException("Only isolated test database is allowed");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->"java_test");
        registry.add("spring.datasource.password",()->"java_test");registry.add("spring.datasource.hikari.read-only",()->false);
        registry.add("researchrunner.writes-enabled",()->true);
    }
    @BeforeEach void boundary() {
        when(embeddings.configuration()).thenReturn(new EmbeddingGateway.Profile("test","test-model",3));
        when(embeddings.embed(anyList())).thenAnswer(call -> ((List<?>)call.getArgument(0)).stream().map(text -> new float[]{1,0,0}).toList());
    }
    private UUID upload(String name,String text) {return UUID.fromString(store.upload(name,"text/plain",text.getBytes(StandardCharsets.UTF_8)).path("id").asText());}

    @Test void actualPgvectorRetrievalHonorsScopeAndDeletedDocuments() throws Exception {
        UUID selected=upload(UUID.randomUUID()+".txt","选定文件的研究证据");
        UUID other=upload(UUID.randomUUID()+".txt","范围外的研究证据");
        index.index(selected);index.index(other);
        var result=index.retrieve("研究",List.of(selected));
        assertEquals(1,result.size());assertEquals(selected.toString(),result.getFirst().get("document_id"));
        assertEquals("选定文件的研究证据",result.getFirst().get("raw_content"));
        clearInvocations(embeddings);
        assertTrue(index.retrieve("研究",List.of()).isEmpty());verifyNoInteractions(embeddings);
        store.delete(selected);
        assertTrue(index.retrieve("研究",List.of(selected)).isEmpty());
    }
    @Test void duplicateUploadIsReusedAndInvalidVectorsDoNotDestroyExistingChunks() throws Exception {
        String name=UUID.randomUUID()+".txt";UUID id=upload(name,"证据内容");index.index(id);
        var duplicate=store.upload(name,"text/plain","证据内容".getBytes(StandardCharsets.UTF_8));
        assertTrue(duplicate.path("reused").asBoolean());assertEquals(id.toString(),duplicate.path("id").asText());
        UUID profile=UUID.fromString(store.metadata(id).path("embedding_profile_id").asText());
        assertThrows(IllegalArgumentException.class,()->store.replace(id,profile,List.of(new DocumentParser.Part("坏向量",1)),List.of(new float[]{1,2})));
        assertEquals("ready",store.metadata(id).path("status").asText());
        assertEquals("证据内容",index.retrieve("研究",List.of(id)).getFirst().get("raw_content"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.profile("other","other",4));
    }
    @Test void multipartUploadIsIndexedAndOriginalFileDownloads() throws Exception {
        byte[] bytes="上传后的中文资料".getBytes(StandardCharsets.UTF_8);
        var body=new LinkedMultiValueMap<String,Object>();
        body.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return UUID.randomUUID()+".txt";}});
        var headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response=http.postForEntity("/api/documents",new HttpEntity<>(body,headers),JsonNode.class);
        assertEquals(200,response.getStatusCode().value());UUID id=UUID.fromString(response.getBody().path("document").path("id").asText());
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(8).toNanos();
        while(store.metadata(id).path("status").asText().equals("processing")&&System.nanoTime()<deadline)Thread.sleep(20);
        assertEquals("ready",store.metadata(id).path("status").asText());
        assertArrayEquals(bytes,http.getForObject("/api/documents/"+id+"/content",byte[].class));
        assertEquals(410,http.exchange("/files/old.txt",HttpMethod.DELETE,null,JsonNode.class).getStatusCode().value());
        http.delete("/api/documents/"+id);
        assertEquals(404,http.getForEntity("/api/documents/"+id+"/content",JsonNode.class).getStatusCode().value());
    }
    @Test void parseFailureMarksDocumentFailedWithoutGeneratingVectors() {
        UUID id=upload(UUID.randomUUID()+".pdf","not pdf");clearInvocations(embeddings);
        assertThrows(Exception.class,()->index.index(id));
        assertEquals("failed",store.metadata(id).path("status").asText());verifyNoInteractions(embeddings);
    }
    @Test void artifactUpsertKeepsIdentityAndServesActualBytes() {
        String path="outputs/"+UUID.randomUUID()+".md";
        var first=artifacts.save("first".getBytes(StandardCharsets.UTF_8),"中文.md","md","text/markdown",null,null,path,Map.of());
        byte[] replacement="第二版".getBytes(StandardCharsets.UTF_8);
        var second=artifacts.save(replacement,"中文.md","md","text/markdown",null,null,path,Map.of());
        assertEquals(first.path("id"),second.path("id"));
        assertArrayEquals(replacement,http.getForObject(second.path("url").asText(),byte[].class));
    }
}
