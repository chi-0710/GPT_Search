package io.researchrunner.research;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit single-writer maintenance operation. Does not repeat paid research or delete index data. */
@Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class InterruptedTaskRecovery {
    private final JdbcTemplate jdbc;
    public InterruptedTaskRecovery(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional public Map<String,Integer> recover() {
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('researchrunner.task_recovery',0))");
        int reports=jdbc.update("""
            WITH interrupted AS (
              UPDATE research_runs SET status='failed',error='Java 服务异常退出，研究任务已中断',completed_at=now(),updated_at=now()
              WHERE status IN ('queued','running') AND request_options->>'execution_backend'='java'
              RETURNING report_id
            ) UPDATE reports SET status='failed',updated_at=now()
              WHERE id IN (SELECT report_id FROM interrupted) AND status IN ('queued','running')
            """);
        int documents=jdbc.update("""
            UPDATE documents SET status='failed',error='服务异常退出，索引已中断，请重新索引',updated_at=now()
            WHERE status='processing'
            """);
        return Map.of("reports",reports,"documents",documents);
    }
}
