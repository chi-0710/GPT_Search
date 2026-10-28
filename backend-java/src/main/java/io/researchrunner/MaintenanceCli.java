package io.researchrunner;

import java.util.Locale;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public final class MaintenanceCli {
    private MaintenanceCli() {}
    public static int reindex(String[] args) {
        try {
            if(args.length>1 || (args.length==1 && !args[0].equals("--dry-run")))throw new IllegalArgumentException("用法：reindex [--dry-run]");
            try(var context=new SpringApplicationBuilder(ResearchRunnerApplication.class).web(WebApplicationType.NONE)
                    .run("--researchrunner.writes-enabled=true","--spring.datasource.hikari.read-only=false","--JAVA_RECOVER_INTERRUPTED_TASKS=false")) {
                var result=context.getBean(io.researchrunner.document.DocumentReindex.class)
                        .rebuild(args.length==1,progress -> System.err.println(format(progress)));
                System.out.println(context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(result));
            }
            return 0;
        }catch(java.lang.InterruptedException cancelled){
            Thread.currentThread().interrupt();
            System.err.println("ResearchRunner 索引维护已取消：staging 已清理，旧索引与文档状态保持不变");
            return 130;
        }catch(Exception error){System.err.println("ResearchRunner 索引维护失败："+error.getClass().getSimpleName()+"（staging 已清理，旧索引保持不变）");return 1;}
    }
    /** CLI-readable one-line progress: phase, processed documents/chunks and elapsed time. */
    static String format(io.researchrunner.document.DocumentReindex.Progress progress) {
        return String.format(Locale.ROOT,"[reindex] phase=%s documents=%d chunks=%d elapsed=%.1fs %s",
                progress.phase(),progress.documents(),progress.chunks(),progress.elapsedMillis()/1000.0,
                progress.detail()==null?"":progress.detail());
    }
}
