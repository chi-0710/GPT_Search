package io.researchrunner.research;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Run-scoped source selection reused by initial and section research. */
public final class SourceCollector {
    @FunctionalInterface public interface McpCollector {List<Map<String,Object>> collect(String query) throws Exception;}
    @FunctionalInterface public interface LocalRetriever {
        List<Map<String,Object>> retrieve(String query,List<UUID> frozenIds) throws Exception;
    }
    @FunctionalInterface public interface Scraper {
        SearchGateway.Hit fetch(String url,List<String> domains) throws Exception;
    }
    public record Selection(String source,List<UUID> documentIds,List<String> domains,List<String> sourceUrls,int limit,
            List<Map<String,Object>> remoteDocuments) {
        public Selection {
            if(!Set.of("local","web","hybrid").contains(source) || limit<1 || limit>20) throw new IllegalArgumentException("研究来源参数无效");
            documentIds=List.copyOf(documentIds);domains=List.copyOf(domains);sourceUrls=List.copyOf(sourceUrls);
            remoteDocuments=List.copyOf(remoteDocuments);
            if(source.equals("local") && documentIds.isEmpty() && remoteDocuments.isEmpty()) throw new IllegalArgumentException("没有可检索的文件");
        }
        public Selection(String source,List<UUID> documentIds,List<String> domains,List<String> sourceUrls,int limit) {
            this(source,documentIds,domains,sourceUrls,limit,List.of());
        }
    }
    public record Collected(String context,List<Integer> local,List<Integer> web) { }
    private final Selection selection;
    private final LocalRetriever local;
    private final SearchGateway search;
    private final Scraper scraper;
    private final EvidenceRegistry evidence;
    private final McpCollector mcp;

    public SourceCollector(Selection selection,LocalRetriever local,SearchGateway search,Scraper scraper,EvidenceRegistry evidence) {
        this(selection,local,search,scraper,evidence,null);
    }
    public SourceCollector(Selection selection,LocalRetriever local,SearchGateway search,Scraper scraper,EvidenceRegistry evidence,McpCollector mcp) {
        this.selection=selection;this.local=local;this.search=search;this.scraper=scraper;this.evidence=evidence;
        this.mcp=mcp;
    }

    /** Frozen source selection of this run (read-only). */
    public Selection selection() {
        return selection;
    }

    /** Frozen source kind of this run: {@code web}, {@code local} or {@code hybrid}. */
    public String source() {
        return selection.source();
    }

    public Collected collect(String query,int localBudget,int webBudget) throws Exception {
        checkCancelled();
        List<Integer> localIds=List.of(),webIds=List.of();
        if(!selection.source().equals("web")) {
            try {
                var selected=new HashSet<>(selection.documentIds());
                var rows=selection.documentIds().isEmpty()?List.<Map<String,Object>>of():local.retrieve(query,selection.documentIds());
                // Enforce frozen scope even when an adapter returns an unexpected row.
                rows=rows.stream().filter(row -> {
                    try{return selected.contains(UUID.fromString(String.valueOf(row.get("document_id"))));}
                    catch(IllegalArgumentException e){return false;}
                }).toList();
                localIds=evidence.register(rows,"document");
            } catch(Exception failure) {propagateCancellation(failure);if(selection.source().equals("local"))throw failure;}
            if(localIds.isEmpty()) evidence.warn("文件来源未提供可用证据"+(selection.source().equals("hybrid")?"，尝试使用网络来源":""));
        }
        checkCancelled();
        List<Integer> remoteIds=evidence.register(selection.remoteDocuments(),"document");
        if(!remoteIds.isEmpty()) {
            var combined=new ArrayList<>(localIds);combined.addAll(remoteIds);localIds=List.copyOf(combined);
        }
        if(!selection.source().equals("local")) {
            List<SearchGateway.Hit> hits=new ArrayList<>();
            try {
                if(selection.sourceUrls().isEmpty()) hits.addAll(search.search(query,selection.domains(),selection.limit()));
                else for(String url:selection.sourceUrls()) hits.add(new SearchGateway.Hit(url,"",""));
                List<Map<String,Object>> sources=new ArrayList<>();
                DomainPolicy policy=new DomainPolicy(selection.domains());
                Set<String> seen=new HashSet<>();
                for(var hit:hits) {
                    checkCancelled();
                    if(!policy.permits(hit.url()) || !seen.add(hit.url()))continue;
                    SearchGateway.Hit usable=hit;
                    try {usable=scraper.fetch(hit.url(),selection.domains());}
                    catch(Exception failure) {
                        propagateCancellation(failure);
                        evidence.warn("部分网页正文抓取失败"+(hit.content().isBlank()?"，已跳过":"，使用搜索摘要并标明来源"));
                    }
                    if(!policy.permits(usable.url()) || usable.content().isBlank())continue;
                    sources.add(Map.of("url",usable.url(),"title",usable.title(),"raw_content",usable.content()));
                    if(sources.size()>=selection.limit())break;
                }
                webIds=evidence.register(sources,"web");
            } catch(Exception failure) {propagateCancellation(failure);if(selection.source().equals("web") && mcp==null)throw failure;evidence.warn("网络检索失败，尝试其他已配置来源");}
            if(mcp!=null) {
                try {
                    var combined=new ArrayList<>(webIds);combined.addAll(evidence.register(mcp.collect(query),"mcp"));webIds=List.copyOf(combined);
                } catch(Exception failure) {
                    propagateCancellation(failure);
                    if(webIds.isEmpty() && localIds.isEmpty())throw failure;
                    evidence.warn("MCP 来源失败，使用其他已取得的证据");
                }
            }
            if(webIds.isEmpty())evidence.warn("网络来源未提供可用证据"+(selection.source().equals("hybrid")?"，尝试使用文件来源":""));
        }
        checkCancelled();
        return new Collected(evidence.context(localIds,webIds,localBudget,webBudget),localIds,webIds);
    }

    private static void checkCancelled() throws InterruptedException {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException("研究已取消");
    }
    private static void propagateCancellation(Exception failure) throws Exception {
        if(failure instanceof InterruptedException || failure instanceof CancellationException)throw failure;
        checkCancelled();
    }
}
