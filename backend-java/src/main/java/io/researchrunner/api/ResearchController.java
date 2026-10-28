package io.researchrunner.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.document.*;
import io.researchrunner.infrastructure.HistoryMapper;
import io.researchrunner.research.*;
import io.researchrunner.report.ReportExports;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** HTTP research adapter. Kept behind the explicit migration write flag. */
@RestController
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class ResearchController {
    private final RunStore runs;private final ReportExports exports;private final DocumentIndex documents;
    private final DocumentStore store;private final EmbeddingGateway embeddings;private final HistoryMapper history;
    private final Environment env;private final ObjectMapper json;
    private final ExecutorService executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),Thread.ofVirtual().factory(),new ThreadPoolExecutor.AbortPolicy());
    public ResearchController(RunStore runs,ReportExports exports,DocumentIndex documents,DocumentStore store,EmbeddingGateway embeddings,HistoryMapper history,Environment env,ObjectMapper json) {
        this.runs=runs;this.exports=exports;this.documents=documents;this.store=store;this.embeddings=embeddings;this.history=history;this.env=env;this.json=json;
    }
    @jakarta.annotation.PreDestroy public void close() throws InterruptedException {executor.shutdownNow();executor.awaitTermination(20,TimeUnit.SECONDS);}
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> invalidRequest(IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.unprocessableEntity().body(Map.of("detail","研究参数或供应商配置无效"));
    }
    @PostMapping("/api/multi_agents") public Map<String,Object> defaultResearch() throws Exception {
        return report(Map.of("task","Is AI in a hype cycle?","report_type","multi_agents","report_source","web","tone","Objective","generate_in_background",false));
    }
    @PostMapping("/report/") public Map<String,Object> report(@RequestBody Map<String,Object> request) throws Exception {
        String query=Objects.toString(request.get("task"),"");String source=Objects.toString(request.getOrDefault("report_source","web"));
        String mode=ReportMode.normalize(Objects.toString(request.getOrDefault("report_type","multi_agents")));
        String tone=Objects.toString(request.getOrDefault("tone","Objective"));
        if(query.isBlank() || !Set.of("web","local","hybrid").contains(source))throw invalid("研究问题或来源无效");
        if(Boolean.TRUE.equals(request.get("mcp_enabled")) || !list(request,"mcp_configs").isEmpty() || !list(request,"document_urls").isEmpty())throw invalid("此 Java 研究入口尚不支持 MCP 或远程文档 URL");
        if(request.get("headers") instanceof Map<?,?> headers && !headers.isEmpty())throw invalid("此入口尚不支持请求级模型配置覆盖");
        if(!env.getProperty("LLM_KWARGS","{}").strip().equals("{}"))throw invalid("此入口尚不支持 LLM_KWARGS");
        if(env.getProperty("IMAGE_GENERATION_ENABLED",Boolean.class,false))throw invalid("此入口尚不支持生成图片");
        List<UUID> scope=new ArrayList<>();
        if(!source.equals("web")) {
            var config=embeddings.configuration();String profile=store.profile(config.provider(),config.model(),config.dimensions()).path("id").asText();
            List<Map<String,Object>> available=new ArrayList<>();for(String row:history.documents())available.add(json.readValue(row,Map.class));
            try {scope=DocumentScope.freeze(available,profile,Objects.toString(request.getOrDefault("document_scope","all")),strings(request,"document_ids"),source).stream().map(UUID::fromString).toList();}
            catch(IllegalArgumentException e){throw invalid(e.getMessage());}
        }
        int limit=request.get("max_search_results") instanceof Number n?n.intValue():env.getProperty("MAX_SEARCH_RESULTS_PER_QUERY",Integer.class,5);
        var selection=new SourceCollector.Selection(source,scope,strings(request,"query_domains"),strings(request,"source_urls"),limit);
        if(!source.equals("local") && !env.getProperty("RETRIEVER","tavily").equals("tavily"))throw invalid("此研究入口尚只接入 Tavily 检索器");
        var usage=new UsageLedger();var model=new SpringResearchModel(binding("FAST",6000),binding("SMART",12000),binding("STRATEGIC",8000),usage,json);
        Map<String,Object> options=new LinkedHashMap<>(request);options.put("resolved_document_ids",scope.stream().map(UUID::toString).toList());
        var run=runs.create(query,mode,source,tone,options);
        Callable<Map<String,Object>> work=()->execute(run,query,tone,selection,model,usage);
        if(!Boolean.FALSE.equals(request.get("generate_in_background"))) {
            try {executor.submit(()->{try{work.call();}catch(Exception ignored){/* execute persists terminal failure */}});}
            catch(RejectedExecutionException e){runs.fail(run.runId(),"研究任务队列已满");throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"研究任务队列已满");}
            return Map.of("message","Your report is being generated in the background. Please check back later.","run_id",run.runId(),"research_id",run.researchId());
        }
        return work.call();
    }
    private Map<String,Object> execute(RunStore.Identity run,String query,String tone,SourceCollector.Selection selection,ResearchNodes.Model model,UsageLedger usage) throws Exception {
        try {
            var evidence=new EvidenceRegistry(run.runId());
            var search=new TavilySearch(HttpClient.newHttpClient(),URI.create("https://api.tavily.com/search"),env.getProperty("TAVILY_API_KEY"),json,Duration.ofSeconds(100));
            var scraper=new WebScraper(Duration.ofSeconds(60),8*1024*1024);
            var sources=new SourceCollector(selection,documents::retrieve,search,scraper::fetch,evidence);
            var paths=new LinkedHashMap<String,String>();
            var settings=new ResearchNodes.Settings(query,tone,List.of(),false,env.getProperty("MAX_SUBTOPICS",Integer.class,3),3,3,10000,10000);
            var nodes=new ResearchNodes(settings,sources,evidence,model,(t,s,r)->new ResearchNodes.Decision(true,""),markdown->markdown,
                    (markdown,snapshots,warnings)->{
                        paths.putAll(exports.save(run.researchId(),run.runId(),markdown,true,true));
                        for(String warning:warnings)runs.append(run.runId(),Map.of("type","logs","content","warning","output",warning));
                        runs.append(run.runId(),Map.of("type","path","output",paths));
                        if(!runs.complete(run.runId(),new ResearchResult(markdown,snapshots,usage.snapshot(),snapshots,warnings)))throw new IllegalStateException("研究已结束，不能发布");
                    },(stage,message)->runs.append(run.runId(),Map.of("type","logs","content",stage,"output",message)),()->Thread.currentThread().isInterrupted());
            runs.append(run.runId(),Map.of("type","research_started","content","research_started","run_id",run.runId(),"research_id",run.researchId(),"output",run.researchId()));
            var result=ResearchWorkflow.compile(nodes.actions(),3,3).invoke(GraphInput.noArgs(),RunnableConfig.empty()).orElseThrow();
            return Map.of("research_id",run.researchId(),"run_id",run.runId(),"report",result.data().get("report"),"evidence_url","/api/research-runs/"+run.runId()+"/evidence","md_path",paths.get("md"),"pdf_path",paths.get("pdf"),"docx_path",paths.get("docx"),"research_information",Map.of("source_urls",evidence.snapshots().stream().map(e->e.get("url")).toList(),"research_costs",usage.snapshot(),"visited_urls",evidence.snapshots().stream().map(e->e.get("url")).toList(),"research_images",List.of()));
        } catch(Exception e){runs.fail(run.runId(),"Java 研究失败："+e.getClass().getSimpleName());throw e;}
    }
    private SpringResearchModel.Binding binding(String role,int tokens) {
        String value=env.getProperty(role+"_LLM");if(value==null || !value.contains(":"))throw invalid(role+"_LLM 必须显式配置供应商和模型");
        String[] parts=value.split(":",2);Map<String,String> variables=new HashMap<>();
        for(String name:List.of("OPENAI_API_KEY","OPENAI_BASE_URL","DEEPSEEK_API_KEY","DASHSCOPE_API_KEY","DASHSCOPE_BASE_URL","OPENROUTER_API_KEY","NEBIUS_API_KEY","NEBIUS_BASE_URL","ATLASCLOUD_API_KEY","VLLM_OPENAI_API_KEY","VLLM_OPENAI_API_BASE","AIMLAPI_API_KEY","FORGE_API_KEY","AVIAN_API_KEY","MINIMAX_API_KEY"))if(env.getProperty(name)!=null)variables.put(name,env.getProperty(name));
        var config=CompatibleModelFactory.settings(parts[0],parts[1],variables);
        return new SpringResearchModel.Binding(parts[0],parts[1],new CompatibleModelFactory().chat(config,env.getProperty(role+"_TOKEN_LIMIT",Integer.class,tokens),env.getProperty("TEMPERATURE",Double.class,0.4)),null);
    }
    private static List<?> list(Map<String,Object> request,String key){return request.get(key) instanceof List<?> values?values:List.of();}
    private static List<String> strings(Map<String,Object> request,String key){return list(request,key).stream().map(Object::toString).toList();}
    private static ResponseStatusException invalid(String detail){return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,detail);}
}
