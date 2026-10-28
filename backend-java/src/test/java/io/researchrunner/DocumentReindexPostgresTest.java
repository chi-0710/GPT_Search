package io.researchrunner;

import io.researchrunner.document.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named="JAVA_WRITE_TEST_DATABASE_URL",matches=".+")
class DocumentReindexPostgresTest {
    private static final String DATABASE="researchrunner_reindex_"+UUID.randomUUID().toString().replace("-","");
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
    @Autowired DocumentIndex index;
    @Autowired DocumentReindex reindex;
    @MockitoSpyBean DocumentStore store;
    @MockitoBean EmbeddingGateway embeddings;
    private UUID id;
    private UUID oldProfile;
    @BeforeEach void data() throws Exception {
        jdbc.update("DELETE FROM documents");jdbc.update("DELETE FROM embedding_profiles");
        when(embeddings.configuration()).thenReturn(new EmbeddingGateway.Profile("test","old",3));
        when(embeddings.embed(anyList())).thenAnswer(call->((List<?>)call.getArgument(0)).stream().map(t->new float[]{1,0,0}).toList());
        id=UUID.fromString(store.upload("资料.txt","text/plain","必须保留的证据".getBytes(java.nio.charset.StandardCharsets.UTF_8)).path("id").asText());
        index.index(id);oldProfile=UUID.fromString(store.metadata(id).path("embedding_profile_id").asText());
        when(embeddings.configuration()).thenReturn(new EmbeddingGateway.Profile("test","new",4));
        when(embeddings.embed(anyList())).thenAnswer(call->((List<?>)call.getArgument(0)).stream().map(t->new float[]{1,0,0,0}).toList());
    }
    @Test void successfulSwitchBuildsIndexAndRejectsStaleWriter() throws Exception {
        assertEquals(true,reindex.rebuild(true).get("dry_run"));
        assertEquals(oldProfile.toString(),store.metadata(id).path("embedding_profile_id").asText());
        var result=reindex.rebuild(false);UUID profile=UUID.fromString(result.get("profile_id").toString());
        assertNotEquals(oldProfile,profile);assertEquals("ready",store.metadata(id).path("status").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname=?",Integer.class,result.get("index")));
        assertEquals("必须保留的证据",index.retrieve("问题",List.of(id)).getFirst().get("raw_content"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.replace(id,oldProfile,
                List.of(new DocumentParser.Part("过期内容",null)),List.of(new float[]{1,0,0})));
        store.failed(id,"旧任务失败");assertEquals("ready",store.metadata(id).path("status").asText());
    }
    @Test void embeddingFailurePreservesOriginalProfileAndChunks() {
        when(embeddings.embed(anyList())).thenThrow(new IllegalStateException("provider unavailable"));
        assertThrows(IllegalStateException.class,()->reindex.rebuild(false));assertOriginal();
    }
    @Test void transactionFailureRollsBackAlreadyReplacedChunks() {
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("simulated storage failure");})
                .when(store).replace(eq(id),any(),anyList(),anyList());
        assertThrows(IllegalStateException.class,()->reindex.rebuild(false));assertOriginal();
    }
    @Test void changedDocumentSnapshotPreventsSwitch() {
        when(embeddings.embed(anyList())).thenAnswer(call->{
            store.upload("新增资料.txt","text/plain","new".getBytes());return List.of(new float[]{1,0,0,0});
        });
        assertThrows(IllegalStateException.class,()->reindex.rebuild(false));assertOriginal();
    }
    private void assertOriginal() {
        assertEquals(oldProfile,jdbc.queryForObject("SELECT id FROM embedding_profiles WHERE active=true",UUID.class));
        assertEquals("必须保留的证据",jdbc.queryForObject("SELECT content FROM document_chunks WHERE document_id=?",String.class,id));
        assertEquals("ready",store.metadata(id).path("status").asText());
    }
}
