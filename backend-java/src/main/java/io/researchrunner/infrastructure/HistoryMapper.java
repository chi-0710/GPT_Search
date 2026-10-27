package io.researchrunner.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only projections of the existing Alembic-managed schema. */
@Mapper
public interface HistoryMapper {
    String REPORT = """
        SELECT jsonb_build_object(
          'id', r.public_id, 'question', r.question, 'answer', r.answer,
          'orderedData', coalesce(r.ordered_data, '[]'::jsonb),
          'timestamp', trunc(extract(epoch from r.updated_at) * 1000)::bigint,
          'status', r.status, 'evidenceUrl', '/api/reports/' || r.public_id || '/evidence',
          'chatMessages', coalesce((SELECT jsonb_agg(
              jsonb_build_object('id', c.id, 'role', c.role, 'content', c.content,
                'timestamp', coalesce(nullif(c.client_timestamp, 0),
                  trunc(extract(epoch from c.created_at) * 1000)::bigint))
              || CASE WHEN c.metadata IS NULL OR c.metadata = 'null'::jsonb THEN '{}'::jsonb
                 ELSE jsonb_build_object('metadata', c.metadata) END
              ORDER BY c.sequence)
            FROM chat_messages c WHERE c.report_id = r.id), '[]'::jsonb)
        )::text FROM reports r
        """;

    @Select("<script>" + REPORT + """
        <where>
          <choose>
            <when test="ids != null">
              r.public_id IN
              <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </when>
            <when test="!includeIncomplete">r.status = 'completed'</when>
          </choose>
          <if test="cursor != null">AND r.updated_at &lt; #{cursor}</if>
        </where>
        ORDER BY r.updated_at DESC LIMIT #{limit}
        </script>
        """)
    List<String> reports(@Param("ids") List<String> ids, @Param("limit") int limit,
            @Param("cursor") OffsetDateTime cursor, @Param("includeIncomplete") boolean includeIncomplete);

    @Select(REPORT + " WHERE r.public_id = #{id}")
    String report(@Param("id") String id);

    @Select("""
        SELECT jsonb_build_object('id', id, 'filename', filename, 'mimeType', mime_type,
          'size', byte_size, 'sha256', sha256, 'status', status, 'error', error,
          'createdAt', created_at, 'updatedAt', updated_at,
          'embedding_profile_id', embedding_profile_id)::text
        FROM documents ORDER BY created_at DESC
        """)
    List<String> documents();

    @Select("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')")
    boolean vectorEnabled();

    @Select("SELECT to_regclass('public.alembic_version') IS NOT NULL")
    boolean migrationTableExists();

    @Select("SELECT version_num FROM alembic_version ORDER BY version_num DESC LIMIT 1")
    String migrationVersion();
}
