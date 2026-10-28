package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.researchrunner.document.*;
import io.researchrunner.infrastructure.HistoryMapper;


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

import org.springframework.web.server.ResponseStatusException;

/** Shared execution for HTTP, WebSocket and CLI adapters. */
@org.springframework.stereotype.Service
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class ResearchExecutionService {
    private final RunStore runs;private final ResearchPublication publication;private final DocumentIndex documents;
    private final DocumentStore store;private final EmbeddingGateway embeddings;private final HistoryMapper history;
    private final Environment env;private final ObjectMapper json;
    private final Set<ResearchConnection> active=ConcurrentHashMap.newKeySet();
    private final ExecutorService executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),Thread.ofVirtual().factory(),new ThreadPoolExecutor.AbortPolicy());
    public ResearchExecutionService(RunStore runs,ResearchPublication publication,DocumentIndex documents,DocumentStore store,EmbeddingGateway embeddings,HistoryMapper history,Environment env,ObjectMapper json) {
        this.runs=runs;this.publication=publication;this.documents=documents;this.store=store;this.embeddings=embeddings;this.history=history;this.env=env;this.json=json;
    }
    @jakarta.annotation.PreDestroy public void close() throws InterruptedException {
        var connections=List.copyOf(active);
        try {for(var connection:connections)connection.close();}
        finally {executor.shutdownNow();}
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        for(var connection:connections)connection.awaitCleanup(Duration.ofNanos(Math.max(1,deadline-System.nanoTime())));
        executor.awaitTermination(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);active.clear();
    }
    public ResearchConnection connection(ResearchConnection.Events events) {return new ResearchConnection(runs,events);}
    public Map<String,Object> report(Map<String,Object> request) throws Exception {
        return report(request,connection(event->{}),false);
    }
    public Map<String,Object> report(Map<String,Object> request,ResearchConnection connection,boolean interactive) throws Exception {
        String query=Objects.toString(request.get("task"),"");String source=Objects.toString(request.getOrDefault("report_source","web"));
        String mode=ReportMode.normalize(Objects.toString(request.getOrDefault("report_type","multi_agents")));
        String tone=Objects.toString(request.getOrDefault("tone","Objective"));
        if(query.isBlank() || !Set.of("web","local","hybrid").contains(source))throw invalid("研究问题或来源无效");
        if(!list(request,"document_urls").isEmpty())throw invalid("此 Java 研究入口尚不支持远程文档 URL");
        var mcpConfigs=McpResearch.parse(list(request,"mcp_configs"),env);
        if(Boolean.TRUE.equals(request.get("mcp_enabled")) && mcpConfigs.isEmpty())throw invalid("启用 MCP 必须提供服务器配置");
        if(!mcpConfigs.isEmpty() && (source.equals("local") || !strings(request,"query_domains").isEmpty() || !strings(request,"source_urls").isEmpty()))
            throw invalid("MCP 暂不支持文件限定或 URL/域名限定研究，禁止扩大来源范围");
        if(!mcpConfigs.isEmpty() && !Objects.toString(request.getOrDefault("mcp_strategy","fast")).equals("fast"))throw invalid("当前 MCP 仅支持 fast 策略");
        if(request.get("headers") instanceof Map<?,?> headers && !headers.isEmpty())throw invalid("此入口尚不支持请求级模型配置覆盖");
        if(!env.getProperty("LLM_KWARGS","{}").strip().equals("{}"))throw invalid("此入口尚不支持 LLM_KWARGS");
        if(env.getProperty("IMAGE_GENERATION_ENABLED",Boolean.class,false))throw invalid("此入口尚不支持生成图片");
        var optionsConfig=ResearchOptions.parse(request,env,query,tone);
        if(optionsConfig.humanReview() && !interactive)throw invalid("人工审核需要 WebSocket 审核通道");
        List<UUID> scope=new ArrayList<>();
        if(!source.equals("web")) {
            var config=embeddings.configuration();String profile=store.profile(config.provider(),config.model(),config.dimensions()).path("id").asText();
            List<Map<String,Object>> available=new ArrayList<>();for(String row:history.documents())available.add(json.readValue(row,Map.class));
            try {scope=DocumentScope.freeze(available,profile,Objects.toString(request.getOrDefault("document_scope","all")),strings(request,"document_ids"),source).stream().map(UUID::fromString).toList();}
            catch(IllegalArgumentException e){throw invalid(e.getMessage());}
        }
        int limit=request.get("max_search_results") instanceof Number n?n.intValue():env.getProperty("MAX_SEARCH_RESULTS_PER_QUERY",Integer.class,5);
        var selection=new SourceCollector.Selection(source,scope,strings(request,"query_domains"),strings(request,"source_urls"),limit);
        String retriever=env.getProperty("RETRIEVER","tavily");
        if(!source.equals("local") && (!Set.of("tavily","mcp").contains(retriever) || (retriever.equals("mcp") && mcpConfigs.isEmpty())))throw invalid("研究检索器未接入或 MCP 配置为空");
        var usage=new UsageLedger();var model=new SpringResearchModel(binding("FAST",6000),binding("SMART",12000),binding("STRATEGIC",8000),usage,json);
        Map<String,Object> options=new LinkedHashMap<>(request);options.put("resolved_document_ids",scope.stream().map(UUID::toString).toList());
        options.put("execution_backend","java");
        options.put("mcp_configs",mcpConfigs.stream().map(c->Map.of("name",c.name(),"transport",c.transport())).toList());
        var run=connection.create(()->runs.create(query,mode,source,tone,options));
        active.add(connection);
        Callable<Map<String,Object>> work=()->{
            try{return execute(run,query,tone,selection,model,usage,optionsConfig,connection,mcpConfigs);}
            finally{active.remove(connection);}
        };
        if(!Boolean.FALSE.equals(request.get("generate_in_background"))) {
            try {
                var task=new FutureTask<Map<String,Object>>(work) {
                    @Override protected void done(){active.remove(connection);}
                };
                connection.task(task);executor.execute(task);
            }
            catch(RejectedExecutionException e){active.remove(connection);connection.failed();runs.fail(run.runId(),"研究任务队列已满");throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"研究任务队列已满");}
            return Map.of("message","Your report is being generated in the background. Please check back later.","run_id",run.runId(),"research_id",run.researchId());
        }
        return work.call();
    }
    private Map<String,Object> execute(RunStore.Identity run,String query,String tone,SourceCollector.Selection selection,ResearchNodes.Model model,UsageLedger usage,ResearchOptions options,ResearchConnection connection,List<McpResearch.Config> mcpConfigs) throws Exception {
        try(var mcp=mcpConfigs.isEmpty()?null:new McpResearch(json,Duration.ofSeconds(60),mcpRoots())) {
            if(mcp!=null)connection.call(()->{mcp.connect(mcpConfigs);return null;});
            var evidence=new EvidenceRegistry(run.runId());
            ResearchNodes.Model cancellableModel=(stage,instruction,input)->connection.call(()->model.generate(stage,instruction,input));
            var search=new TavilySearch(HttpClient.newHttpClient(),URI.create("https://api.tavily.com/search"),env.getProperty("TAVILY_API_KEY"),json,Duration.ofSeconds(100));
            var scraper=new WebScraper(Duration.ofSeconds(60),8*1024*1024);
            var sources=new SourceCollector(selection,
                    (q,ids)->connection.call(()->documents.retrieve(q,ids)),
                    (q,domains,limit)->env.getProperty("RETRIEVER","tavily").equals("mcp")?List.of():connection.call(()->search.search(q,domains,limit)),
                    (url,domains)->connection.call(()->scraper.fetch(url,domains)),evidence,
                    mcp==null?null:q->connection.call(()->mcp.collect(q,cancellableModel)));
            var paths=new LinkedHashMap<String,String>();
            var settings=options.nodes();
            ResearchNodes.Progress progress=(stage,message)->connection.emit(Map.of("type","logs","content",stage,"output",message));
            ResearchNodes.Human human=options.humanReview()?connection.human():(title,sections,round)->{
                progress.emit("human_review_skipped","未启用人工审核");return new ResearchNodes.Decision(true,"");
            };
            var figures=new ArrayList<io.researchrunner.report.ReportFigure>();
            ResearchNodes.Visualizer visualizer=env.getProperty("JAVA_VISUALIZATION_ENABLED",Boolean.class,true)
                    ?new ResearchVisualization(cancellableModel,evidence,progress,run.runId(),figures::add,env.getProperty("PUBLIC_API_URL","http://localhost:8000"))
                    :markdown->{progress.emit("visualization_skipped","配置已禁用可视化");return markdown;};
            var nodes=new ResearchNodes(settings,sources,evidence,cancellableModel,human,visualizer,
                    (markdown,snapshots,warnings)->{
                        paths.putAll(connection.publish(publication,new ResearchResult(markdown,snapshots,usage.snapshot(),snapshots,warnings),options.pdf(),options.docx(),figures));
                    },progress,connection::cancelled);
            connection.emit(Map.of("type","research_started","content","research_started","run_id",run.runId(),"research_id",run.researchId(),"output",run.researchId()));
            var result=connection.call(()->ResearchWorkflow.compile(nodes.actions(),options.planLimit(),options.factLimit()).invoke(GraphInput.noArgs(),RunnableConfig.empty()).orElseThrow());
            return Map.of("research_id",run.researchId(),"run_id",run.runId(),"report",result.data().get("report"),"evidence_url","/api/research-runs/"+run.runId()+"/evidence","md_path",paths.get("md"),"pdf_path",paths.getOrDefault("pdf",""),"docx_path",paths.getOrDefault("docx",""),"research_information",Map.of("source_urls",evidence.snapshots().stream().map(e->e.get("url")).toList(),"research_costs",usage.snapshot(),"visited_urls",evidence.snapshots().stream().map(e->e.get("url")).toList(),"research_images",List.of()));
        } catch(Exception e){
            if(connection.cancelled() || e instanceof CancellationException || e instanceof InterruptedException)connection.close();
            else {
                try{connection.emit(Map.of("type","error","output","Java 研究失败："+e.getClass().getSimpleName()));}catch(Exception ignored){}
                runs.fail(run.runId(),"Java 研究失败："+e.getClass().getSimpleName());connection.failed();
            }
            throw e;
        }
    }
    private SpringResearchModel.Binding binding(String role,int tokens) {return new ConfiguredChatModels(env).binding(role,tokens);}
    private List<java.nio.file.Path> mcpRoots() throws java.io.IOException {
        List<java.nio.file.Path> roots=new ArrayList<>();
        for(String root:env.getProperty("JAVA_MCP_ALLOWED_ROOTS","").split(";"))if(!root.isBlank())roots.add(java.nio.file.Path.of(root).toRealPath());
        return roots;
    }
    private static List<?> list(Map<String,Object> request,String key){return request.get(key) instanceof List<?> values?values:List.of();}
    private static List<String> strings(Map<String,Object> request,String key){return list(request,key).stream().map(Object::toString).toList();}
    private static ResponseStatusException invalid(String detail){return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,detail);}
}
