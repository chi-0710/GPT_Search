package io.researchrunner.infrastructure;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

/** Mutations of the existing schema; enabled only through the guarded write service. */
@Mapper
public interface ReportWriteMapper {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{id}, 0))")
    String lockIdentity(@Param("id") String id);

    @Select("SELECT row_to_json(r)::text FROM reports r WHERE public_id = #{id} FOR UPDATE")
    String lockedReport(@Param("id") String id);

    @Insert("""
        INSERT INTO reports(id, public_id, question, answer, ordered_data, status, created_at, updated_at)
        VALUES(#{uuid}, #{id}, '', '', '[]'::jsonb, 'queued', #{time}, #{time})
        ON CONFLICT(public_id) DO NOTHING
        """)
    void create(@Param("uuid") UUID uuid, @Param("id") String id, @Param("time") OffsetDateTime time);

    @Update("""
        UPDATE reports SET question=#{question}, answer=#{answer}, ordered_data=CAST(#{ordered} AS jsonb),
          status=#{status}, updated_at=#{updated}, completed_at=#{completed} WHERE id=#{id}
        """)
    void update(@Param("id") UUID id, @Param("question") String question, @Param("answer") String answer,
            @Param("ordered") String ordered, @Param("status") String status,
            @Param("updated") OffsetDateTime updated, @Param("completed") OffsetDateTime completed);

    @Delete("DELETE FROM chat_messages WHERE report_id=#{report}")
    void clearChat(@Param("report") UUID report);

    @Select("SELECT report_id::text FROM chat_messages WHERE id=#{id}")
    String messageOwner(@Param("id") UUID id);

    @Select("SELECT coalesce(max(sequence),0)+1 FROM chat_messages WHERE report_id=#{report}")
    long nextSequence(@Param("report") UUID report);

    @Insert("""
        INSERT INTO chat_messages(id, report_id, sequence, role, content, metadata, client_timestamp)
        VALUES(#{id}, #{report}, #{sequence}, #{role}, #{content}, CAST(#{metadata} AS jsonb), #{timestamp})
        """)
    void insertChat(@Param("id") UUID id, @Param("report") UUID report, @Param("sequence") long sequence,
            @Param("role") String role, @Param("content") String content,
            @Param("metadata") String metadata, @Param("timestamp") Long timestamp);

    @Update("UPDATE reports SET updated_at=#{time} WHERE id=#{id}")
    void touch(@Param("id") UUID id, @Param("time") OffsetDateTime time);

    @Delete("DELETE FROM reports WHERE public_id=#{id}")
    int delete(@Param("id") String id);

    @Delete("DELETE FROM reports")
    int clearReports();

    @Delete("DELETE FROM research_runs")
    void clearRuns();

    @Delete("DELETE FROM artifacts")
    void clearArtifacts();
}
