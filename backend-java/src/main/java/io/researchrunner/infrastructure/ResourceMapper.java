package io.researchrunner.infrastructure;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ResourceMapper {
    String RUN = """
        SELECT jsonb_build_object('id', r.id, 'research_id', coalesce(p.public_id, ''),
          'query', r.query, 'report_type', r.report_type, 'report_source', r.report_source,
          'tone', r.tone, 'status', r.status, 'error', r.error,
          'costs', coalesce(r.cost_summary, '{}'::jsonb),
          'request_options', coalesce(r.request_options, '{}'::jsonb),
          'started_at', r.started_at, 'completed_at', r.completed_at)::text
        FROM research_runs r LEFT JOIN reports p ON p.id = r.report_id
        """;

    @Select(RUN + " WHERE r.id = #{id}")
    String runById(@Param("id") UUID id);

    @Select(RUN + " WHERE p.public_id = #{id} ORDER BY r.started_at DESC, r.created_at DESC LIMIT 1")
    String runByReport(@Param("id") String id);

    @Select("""
        SELECT (payload || jsonb_build_object('id', id, 'createdAt', created_at))::text
        FROM research_events WHERE run_id = #{runId} AND id > #{afterId}
        ORDER BY id LIMIT #{limit}
        """)
    List<String> events(@Param("runId") UUID runId, @Param("afterId") long afterId, @Param("limit") int limit);

    @Select("""
        SELECT (e.snapshot || jsonb_build_object('run_id', e.run_id,
          'original_available', coalesce(e.snapshot->>'source_type' = 'web', false)
            OR EXISTS (SELECT 1 FROM documents d WHERE d.id::text = e.snapshot->>'document_id')))::text
        FROM research_evidence e WHERE e.run_id = #{runId} ORDER BY e.number
        """)
    List<String> evidence(@Param("runId") UUID runId);

    @Select("""
        SELECT jsonb_build_object('url', url, 'title', title, 'raw_content', raw_content,
          'source_type', source_type, 'metadata', coalesce(metadata, '{}'::jsonb))::text
        FROM research_sources WHERE run_id = #{runId} ORDER BY created_at, id
        """)
    List<String> sources(@Param("runId") UUID runId);

    @ConstructorArgs({@Arg(column="filename", javaType=String.class),
        @Arg(column="mime_type", javaType=String.class), @Arg(column="sha256", javaType=String.class),
        @Arg(column="content", javaType=byte[].class)})
    @Select("SELECT filename, mime_type, sha256, content FROM artifacts WHERE id = #{id}")
    StoredFile artifact(@Param("id") UUID id);

    @ConstructorArgs({@Arg(column="filename", javaType=String.class),
        @Arg(column="mime_type", javaType=String.class), @Arg(column="sha256", javaType=String.class),
        @Arg(column="content", javaType=byte[].class)})
    @Select("SELECT filename, mime_type, sha256, content FROM documents WHERE id = #{id}")
    StoredFile document(@Param("id") UUID id);

    @ConstructorArgs({@Arg(column="filename", javaType=String.class),
        @Arg(column="mime_type", javaType=String.class), @Arg(column="sha256", javaType=String.class),
        @Arg(column="content", javaType=byte[].class)})
    @Select("""
        SELECT a.filename, a.mime_type, a.sha256, a.content FROM artifacts a
        JOIN reports r ON a.report_id = r.id WHERE r.public_id = #{id} AND a.kind = 'docx'
        ORDER BY a.created_at DESC LIMIT 1
        """)
    StoredFile reportDocument(@Param("id") String id);

    @ConstructorArgs({@Arg(column="filename", javaType=String.class),
        @Arg(column="mime_type", javaType=String.class), @Arg(column="sha256", javaType=String.class),
        @Arg(column="content", javaType=byte[].class)})
    @Select("""
        SELECT filename, mime_type, sha256, content FROM artifacts
        WHERE legacy_path = #{path} OR legacy_path = #{prefixed} ORDER BY created_at DESC LIMIT 1
        """)
    StoredFile legacyArtifact(@Param("path") String path, @Param("prefixed") String prefixed);
}
