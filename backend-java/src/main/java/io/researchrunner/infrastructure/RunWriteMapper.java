package io.researchrunner.infrastructure;

import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RunWriteMapper {
    @Insert("""
        INSERT INTO research_runs(id,report_id,query,report_type,report_source,tone,request_options,
          cost_summary,status,started_at,created_at,updated_at)
        VALUES(#{id},#{report},#{query},'multi_agents',#{source},#{tone},CAST(#{options} AS jsonb),
          '{}'::jsonb,'running',now(),now(),now())
        """)
    void create(@Param("id") UUID id, @Param("report") UUID report, @Param("query") String query,
            @Param("source") String source, @Param("tone") String tone, @Param("options") String options);

    @Select("SELECT row_to_json(r)::text FROM research_runs r WHERE id=#{id} FOR UPDATE")
    String locked(@Param("id") UUID id);

    @Select(value="""
        INSERT INTO research_events(run_id,event_type,content_key,payload)
        VALUES(#{id},#{type},#{key},CAST(#{payload} AS jsonb)) RETURNING id
        """, affectData=true)
    long append(@Param("id") UUID id, @Param("type") String type,
            @Param("key") String key, @Param("payload") String payload);

    @Update("""
        UPDATE research_runs SET status=#{status}, error=#{error}, cost_summary=CAST(#{costs} AS jsonb),
        completed_at=now(),updated_at=now() WHERE id=#{id}
        """)
    void finish(@Param("id") UUID id, @Param("status") String status,
            @Param("error") String error, @Param("costs") String costs);

    @Update("""
        UPDATE reports SET answer=coalesce(#{answer},answer),status=#{status},updated_at=now(),
        completed_at=CASE WHEN #{status}='completed' THEN now() ELSE completed_at END,
        ordered_data=coalesce((SELECT jsonb_agg(payload ORDER BY id) FROM research_events WHERE run_id=#{run}), '[]'::jsonb)
        WHERE id=#{report}
        """)
    void finishReport(@Param("run") UUID run, @Param("report") UUID report,
            @Param("answer") String answer, @Param("status") String status);

    @Insert("""
        INSERT INTO research_evidence(run_id,number,snapshot) VALUES(#{id},#{number},CAST(#{snapshot} AS jsonb))
        """)
    void evidence(@Param("id") UUID id, @Param("number") int number, @Param("snapshot") String snapshot);

    @Insert("""
        INSERT INTO research_sources(id,run_id,url,url_hash,title,raw_content,source_type,metadata)
        VALUES(#{id},#{run},#{url},#{hash},#{title},#{content},#{type},CAST(#{metadata} AS jsonb))
        ON CONFLICT(run_id,url_hash) DO NOTHING
        """)
    void source(@Param("id") UUID id, @Param("run") UUID run, @Param("url") String url,
            @Param("hash") String hash, @Param("title") String title,
            @Param("content") String content, @Param("type") String type, @Param("metadata") String metadata);
}
