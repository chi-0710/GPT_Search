package io.researchrunner;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

/**
 * A4-11: idempotent writes used only by {@link LegacyImportService}.
 *
 * <p>Every statement mirrors the Python {@code PostgresStore} helpers used by
 * {@code scripts/migrate_legacy_storage.py}: report identity lookups, the
 * "ensure imported run" pair of inserts, append-only event writes, artifact-independent
 * migration bookkeeping and the {@code migration_imports} ledger keyed by
 * {@code (source_path, sha256)}.
 */
@Mapper
public interface LegacyWriteMapper {
    /** Python {@code select(Report).where(Report.public_id == public_id)}. */
    @Select("SELECT id::text FROM reports WHERE public_id=#{publicId}")
    String reportId(@Param("publicId") String publicId);

    /** Python {@code select(ResearchRun).where(ResearchRun.report_id == report.id)} (oldest run wins). */
    @Select("""
        SELECT id::text FROM research_runs WHERE report_id=#{reportId}
        ORDER BY created_at LIMIT 1
        """)
    String runForReport(@Param("reportId") UUID reportId);

    @Select("SELECT id::text FROM research_runs WHERE id=#{runId}")
    String runById(@Param("runId") UUID runId);

    /** True only for runs this importer created ({@code request_options.origin == legacy_import}). */
    @Select("""
        SELECT count(*) FROM research_runs
        WHERE id=#{runId} AND request_options->>'origin'='legacy_import'
        """)
    int importedRun(@Param("runId") UUID runId);

    @Insert("""
        INSERT INTO research_runs(id,report_id,query,report_type,report_source,tone,request_options,
          cost_summary,status,started_at,completed_at,created_at,updated_at)
        VALUES(#{id},#{reportId},#{query},'research_report','legacy','Objective',
          CAST('{"origin":"legacy_import"}' AS jsonb),'{}'::jsonb,'completed',
          #{time},#{time},#{time},#{time})
        ON CONFLICT DO NOTHING
        """)
    void insertImportedRun(@Param("id") UUID id, @Param("reportId") UUID reportId,
            @Param("query") String query, @Param("time") OffsetDateTime time);

    /** Orphaned legacy outputs share one archive run that has no report. */
    @Insert("""
        INSERT INTO research_runs(id,report_id,query,report_type,report_source,tone,request_options,
          cost_summary,status,started_at,completed_at,created_at,updated_at)
        VALUES(#{id},NULL,#{query},'research_report','legacy','Objective',
          CAST('{"origin":"legacy_import"}' AS jsonb),'{}'::jsonb,'completed',
          #{time},#{time},#{time},#{time})
        ON CONFLICT DO NOTHING
        """)
    void insertOrphanRun(@Param("id") UUID id, @Param("query") String query,
            @Param("time") OffsetDateTime time);

    @Select("SELECT count(*) FROM research_events WHERE run_id=#{runId}")
    long eventCount(@Param("runId") UUID runId);

    /** Same truncation rules as Python {@code PostgresStore.append_event}. */
    @Insert("""
        INSERT INTO research_events(run_id,event_type,content_key,payload)
        VALUES(#{runId},#{type},#{key},CAST(#{payload} AS jsonb))
        """)
    void appendEvent(@Param("runId") UUID runId, @Param("type") String type,
            @Param("key") String key, @Param("payload") String payload);

    /** Python {@code get_migration_import}: matched by both path and content hash. */
    @Select("""
        SELECT jsonb_build_object('status',status,'error',error,'details',details)::text
        FROM migration_imports WHERE source_path=#{sourcePath} AND sha256=#{sha256}
        """)
    String findImport(@Param("sourcePath") String sourcePath, @Param("sha256") String sha256);

    /** Python {@code record_migration_import}: upsert on {@code uq_migration_import_source_hash}. */
    @Insert("""
        INSERT INTO migration_imports(id,source_path,sha256,status,error,details)
        VALUES(#{id},#{sourcePath},#{sha256},#{status},#{error},CAST(#{details} AS jsonb))
        ON CONFLICT(source_path,sha256) DO UPDATE SET
          status=excluded.status,error=excluded.error,details=excluded.details
        """)
    void recordImport(@Param("id") UUID id, @Param("sourcePath") String sourcePath,
            @Param("sha256") String sha256, @Param("status") String status,
            @Param("error") String error, @Param("details") String details);
}
