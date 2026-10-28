package io.researchrunner;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public final class MaintenanceCli {
    private MaintenanceCli() {}
    public static int reindex(String[] args) {
        try {
            if(args.length>1 || (args.length==1 && !args[0].equals("--dry-run")))throw new IllegalArgumentException("用法：reindex [--dry-run]");
            try(var context=new SpringApplicationBuilder(ResearchRunnerApplication.class).web(WebApplicationType.NONE)
                    .run("--researchrunner.writes-enabled=true","--spring.datasource.hikari.read-only=false","--JAVA_RECOVER_INTERRUPTED_TASKS=false")) {
                var result=context.getBean(io.researchrunner.document.DocumentReindex.class).rebuild(args.length==1);
                System.out.println(context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(result));
            }
            return 0;
        }catch(Exception error){System.err.println("ResearchRunner 索引维护失败："+error.getClass().getSimpleName());return 1;}
    }
}
