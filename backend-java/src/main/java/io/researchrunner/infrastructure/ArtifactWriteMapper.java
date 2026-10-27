package io.researchrunner.infrastructure;

import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ArtifactWriteMapper {
    @Select(value="""
        INSERT INTO artifacts(id,report_id,run_id,kind,filename,mime_type,byte_size,sha256,content,legacy_path,metadata)
        VALUES(#{id},(SELECT id FROM reports WHERE public_id=#{report}),#{run},#{kind},#{name},#{mime},#{size},#{hash},#{bytes},#{path},CAST(#{metadata} AS jsonb))
        ON CONFLICT(legacy_path) DO UPDATE SET content=excluded.content,byte_size=excluded.byte_size,
          sha256=excluded.sha256,mime_type=excluded.mime_type,kind=excluded.kind,
          report_id=coalesce(excluded.report_id,artifacts.report_id),run_id=coalesce(excluded.run_id,artifacts.run_id)
        RETURNING jsonb_build_object('id',id,'kind',kind,'filename',filename,'mimeType',mime_type,
          'size',byte_size,'sha256',sha256,'legacyPath',legacy_path,'url','/api/artifacts/' || id)::text
        """,affectData=true)
    String save(@Param("id") UUID id,@Param("report") String report,@Param("run") UUID run,@Param("kind") String kind,
            @Param("name") String name,@Param("mime") String mime,@Param("size") int size,@Param("hash") String hash,
            @Param("bytes") byte[] bytes,@Param("path") String path,@Param("metadata") String metadata);
}
