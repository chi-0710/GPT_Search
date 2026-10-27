package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Tavily adapter; transport failures are propagated so hybrid fallback can emit a warning. */
public final class TavilySearch implements SearchGateway {
    private static final Pattern SITE = Pattern.compile("site:(\\S+)", Pattern.CASE_INSENSITIVE);
    private final HttpClient client;
    private final URI endpoint;
    private final String key;
    private final ObjectMapper json;
    private final Duration timeout;

    public TavilySearch(HttpClient client, URI endpoint, String key, ObjectMapper json, Duration timeout) {
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())))
            throw new IllegalArgumentException("搜索服务地址无效");
        this.client=client; this.endpoint=endpoint; this.key=key; this.json=json; this.timeout=timeout;
    }

    @Override public List<Hit> search(String query, List<String> domains, int limit) throws Exception {
        if (query == null || query.isBlank() || limit < 1 || limit > 20) throw new IllegalArgumentException("搜索参数无效");
        DomainPolicy policy = new DomainPolicy(domains);
        if (policy.deniesAll()) return List.of();
        List<String> generated = new ArrayList<>();
        var matcher = SITE.matcher(query);
        boolean hasSite = false;
        while (matcher.find()) {
            hasSite = true;
            String host = DomainPolicy.hostname(matcher.group(1).replaceAll("[,)]*$", ""), true);
            if (host != null && policy.permits("https://" + host)) generated.add(host);
        }
        if (hasSite && generated.isEmpty() && policy.hosts().isEmpty()) return List.of();
        // Request restrictions are authoritative; generated site operators cannot widen them.
        List<String> includes = !policy.hosts().isEmpty() ? policy.hosts() : generated.stream().distinct().toList();
        DomainPolicy resultPolicy = includes.isEmpty() ? policy : new DomainPolicy(includes);
        query = SITE.matcher(query).replaceAll("").strip();
        if (query.isBlank()) throw new IllegalArgumentException("搜索问题不能为空");
        if (key == null || key.isBlank()) throw new IllegalStateException("TAVILY_API_KEY 未配置");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("搜索已取消");
        int length = query.codePointCount(0, query.length());
        if (length > 400) query = query.substring(0, query.offsetByCodePoints(0,400));
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("query",query); body.put("search_depth","basic"); body.put("topic","general");
        body.put("days",2); body.put("include_answer",false); body.put("include_raw_content",false);
        body.put("max_results",limit); body.put("include_domains",includes); body.put("exclude_domains",List.of());
        body.put("include_images",false); body.put("api_key",key); body.put("use_cache",true);
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        var response = client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (var stream=response.body()) {
            if (response.statusCode()!=200) throw new IllegalStateException("Tavily 搜索失败，HTTP " + response.statusCode());
            byte[] bytes=stream.readNBytes(4*1024*1024+1);
            if (bytes.length>4*1024*1024) throw new IllegalStateException("搜索响应过大");
            var root=json.readTree(bytes);
            if (!root.path("results").isArray()) throw new IllegalStateException("搜索响应缺少结果列表");
            Map<String,Hit> result=new LinkedHashMap<>();
            for (var hit:root.path("results")) {
                String url=hit.path("url").asText("");
                if (!resultPolicy.permits(url)) continue;
                String content=hit.path("content").asText("");
                if (content.isBlank()) content=hit.path("snippet").asText("");
                result.putIfAbsent(url,new Hit(url,hit.path("title").asText(""),content));
                if(result.size()>=limit) break;
            }
            return List.copyOf(result.values());
        }
    }
}
