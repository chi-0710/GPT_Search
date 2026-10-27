package io.researchrunner.infrastructure;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentWriteMapper {
    String META = """
        SELECT jsonb_build_object('id',id,'filename',filename,'mimeType',mime_type,'size',byte_size,
          'sha256',sha256,'status',status,'error',error,'embedding_profile_id',embedding_profile_id,
          'createdAt',created_at,'updatedAt',updated_at)::text FROM documents
        """;

    @Select(value="""
        INSERT INTO documents(id,filename,mime_type,byte_size,sha256,content,status,created_at,updated_at)
        VALUES(#{id},#{name},#{mime},#{size},#{hash},#{content},'processing',now(),now())
        ON CONFLICT(filename,sha256) DO NOTHING RETURNING id::text
        """, affectData=true)
    String insert(@Param("id") UUID id,@Param("name") String name,@Param("mime") String mime,
            @Param("size") int size,@Param("hash") String hash,@Param("content") byte[] content);

    @Select(META + " WHERE filename=#{name} AND sha256=#{hash}")
    String byDigest(@Param("name") String name,@Param("hash") String hash);
    @Select(META + " WHERE id=#{id}")
    String metadata(@Param("id") UUID id);
    @Select("SELECT id::text FROM documents WHERE id=#{id} FOR UPDATE")
    String lock(@Param("id") UUID id);
    @Delete("DELETE FROM documents WHERE id=#{id}")
    int delete(@Param("id") UUID id);
    @Update("UPDATE documents SET status=#{status},error=#{error},updated_at=now() WHERE id=#{id}")
    int status(@Param("id") UUID id,@Param("status") String status,@Param("error") String error);
    @Select("SELECT pg_advisory_xact_lock(hashtextextended('researchrunner.embedding_profile',0))")
    String profileLock();
    @Select("SELECT row_to_json(p)::text FROM embedding_profiles p WHERE active=true FOR UPDATE")
    String activeProfile();
    @Select("SELECT row_to_json(p)::text FROM embedding_profiles p WHERE id=#{id}")
    String profile(@Param("id") UUID id);
    @Select(value="""
        INSERT INTO embedding_profiles(id,provider,model,dimensions,active,created_at,updated_at)
        VALUES(#{id},#{provider},#{model},#{dimensions},true,now(),now())
        ON CONFLICT(provider,model,dimensions) DO UPDATE SET active=true,updated_at=now()
        RETURNING row_to_json(embedding_profiles)::text
        """,affectData=true)
    String createProfile(@Param("id") UUID id,@Param("provider") String provider,@Param("model") String model,@Param("dimensions") int dimensions);
    @Delete("DELETE FROM document_chunks WHERE document_id=#{id}")
    void clearChunks(@Param("id") UUID id);
    @Insert("""
        INSERT INTO document_chunks(document_id,embedding_profile_id,chunk_index,content,metadata,embedding)
        VALUES(#{document},#{profile},#{index},#{text},CAST(#{metadata} AS jsonb),CAST(#{vector} AS vector))
        """)
    void chunk(@Param("document") UUID document,@Param("profile") UUID profile,@Param("index") int index,
            @Param("text") String text,@Param("metadata") String metadata,@Param("vector") String vector);
    @Update("UPDATE documents SET embedding_profile_id=#{profile},status='ready',error=null,updated_at=now() WHERE id=#{id}")
    void ready(@Param("id") UUID id,@Param("profile") UUID profile);

    @Select("""
        <script>
        WITH scoped AS MATERIALIZED (SELECT c.*,d.filename FROM document_chunks c JOIN documents d ON d.id=c.document_id
        WHERE c.embedding_profile_id=#{profile} AND d.status='ready' AND d.embedding_profile_id=#{profile}
        AND d.id IN <foreach item="id" collection="ids" open="(" separator="," close=")">CAST(#{id} AS uuid)</foreach>
        )
        SELECT jsonb_build_object('document_id',c.document_id,'chunk_id',c.id::text,'raw_content',c.content,
          'title',c.filename,'url',c.filename,'metadata',c.metadata,
          'page',c.metadata->'page_number','similarity',1-(c.embedding &lt;=&gt; CAST(#{vector} AS vector)))::text
        FROM scoped c WHERE 1-(c.embedding &lt;=&gt; CAST(#{vector} AS vector)) &gt;= #{threshold}
        ORDER BY c.embedding &lt;=&gt; CAST(#{vector} AS vector),c.id LIMIT #{limit}
        </script>
        """)
    List<String> search(@Param("profile") UUID profile,@Param("ids") List<String> ids,@Param("vector") String vector,
            @Param("limit") int limit,@Param("threshold") double threshold);
}
