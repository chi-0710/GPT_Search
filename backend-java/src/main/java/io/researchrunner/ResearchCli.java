package io.researchrunner;

import io.researchrunner.research.*;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;

/** Java CLI uses the same persisted research graph and artifact publication as HTTP. */
public final class ResearchCli {
    private ResearchCli() {}
    public static Map<String,Object> parse(String[] args) {
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("report_type","multi_agents");request.put("report_source","web");request.put("tone","Objective");
        request.put("generate_in_background",false);request.put("query_domains",List.of());
        String query=null;
        for(int i=0;i<args.length;i++) {
            String arg=args[i];
            if(arg.equals("--no-pdf")){request.put("export_pdf",false);continue;}
            if(arg.equals("--no-docx")){request.put("export_docx",false);continue;}
            if(!arg.startsWith("--")) {if(query!=null)throw new IllegalArgumentException("只允许一个研究问题");query=arg;continue;}
            String key=arg.substring(2);String value;
            int equals=key.indexOf('=');
            if(equals>=0){value=key.substring(equals+1);key=key.substring(0,equals);}
            else {if(++i==args.length || args[i].startsWith("--"))throw new IllegalArgumentException("参数缺少值："+key);value=args[i];}
            switch(key) {
                case "report_type" -> request.put(key,ReportMode.normalize(value));
                case "report_source" -> {if(!Set.of("web","local","hybrid").contains(value))throw new IllegalArgumentException("研究来源无效");request.put(key,value);}
                case "tone" -> {
                    if(!Set.of("objective","formal","analytical","persuasive","informative","explanatory","descriptive","critical",
                            "comparative","speculative","reflective","narrative","humorous","optimistic","pessimistic").contains(value))
                        throw new IllegalArgumentException("语气无效");
                    request.put(key,value.substring(0,1).toUpperCase(Locale.ROOT)+value.substring(1));
                }
                case "query_domains" -> request.put(key,Arrays.stream(value.split(",")).map(String::strip).filter(s->!s.isEmpty()).toList());
                case "encoding" -> {
                    // Legacy CLI accepts this argument but persisted Markdown has always been UTF-8.
                    java.nio.charset.Charset.forName(value);
                }
                default -> throw new IllegalArgumentException("未知参数："+key);
            }
        }
        if(query==null || query.isBlank())throw new IllegalArgumentException("需要研究问题");
        request.put("task",query);return Collections.unmodifiableMap(request);
    }
    public static int run(String[] args) {
        if(Arrays.asList(args).contains("--help")) {
            System.out.println("ResearchRunner: java -jar researchrunner-api.jar research \"研究问题\" [--report_type multi_agents] [--report_source web|local|hybrid] [--tone objective] [--encoding utf-8] [--query_domains example.com] [--no-pdf] [--no-docx]");
            return 0;
        }
        try {
            var request=parse(args);
            try(var context=new SpringApplicationBuilder(ResearchRunnerApplication.class).web(WebApplicationType.NONE)
                    .run("--researchrunner.writes-enabled=true","--spring.datasource.hikari.read-only=false")) {
                var result=context.getBean(ResearchExecutionService.class).report(request);
                System.out.println(context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(result));
            }
            return 0;
        } catch(Exception failure){System.err.println("ResearchRunner CLI 执行失败："+failure.getClass().getSimpleName());return 1;}
    }
}
