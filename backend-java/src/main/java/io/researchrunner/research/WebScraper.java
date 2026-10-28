package io.researchrunner.research;

import io.researchrunner.document.DocumentParser;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;

/** Bounded HTML/PDF fetching; each redirect must still satisfy the request domain policy. */
public final class WebScraper {
    private final int timeoutMillis;
    private final int maxBytes;
    private final boolean allowPrivateHosts;
    public WebScraper(Duration timeout, int maxBytes) {
        this(timeout, maxBytes, false);
    }
    public WebScraper(Duration timeout, int maxBytes, boolean allowPrivateHosts) {
        if(timeout.isNegative() || timeout.isZero() || maxBytes<1) throw new IllegalArgumentException("抓取限制无效");
        this.timeoutMillis=Math.toIntExact(timeout.toMillis()); this.maxBytes=maxBytes;
        this.allowPrivateHosts=allowPrivateHosts;
    }

    public SearchGateway.Hit fetch(String url,List<String> domains) throws Exception {
        var policy=new DomainPolicy(domains);
        for(int redirect=0;redirect<=5;redirect++) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException("抓取已取消");
            if(!policy.permits(url)) throw new IllegalArgumentException("网页不在允许的域名范围内");
            // A6-04: block loopback/private/link-local targets unless a caller explicitly opts in
            // (tests use a local HTTP server; production scraping only reaches public hosts).
            if(!allowPrivateHosts && !RemoteDocuments.isPublicHttp(URI.create(url)))
                throw new IllegalArgumentException("网页地址不允许访问（内网或非 HTTP 地址）");
            var response=Jsoup.connect(url).userAgent("ResearchRunner/1.0")
                    .timeout(timeoutMillis).maxBodySize(maxBytes+1).followRedirects(false)
                    .ignoreContentType(true).ignoreHttpErrors(true).execute();
            if(response.statusCode()>=300 && response.statusCode()<400) {
                String location=response.header("Location");
                if(location==null) throw new IllegalStateException("网页重定向缺少地址");
                url=URI.create(url).resolve(location).toString();continue;
            }
            if(response.statusCode()!=200) throw new IllegalStateException("网页抓取失败，HTTP "+response.statusCode());
            byte[] bytes=response.bodyAsBytes();
            if(bytes.length>maxBytes) throw new IllegalStateException("网页内容超过大小限制");
            String type=response.contentType()==null?"":response.contentType().toLowerCase(Locale.ROOT);
            String content,title;
            if(type.contains("application/pdf") || URI.create(url).getPath().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                content=String.join("\n\n",new DocumentParser().parse("source.pdf",bytes).stream().map(DocumentParser.Part::content).toList());
                title=url;
            } else {
                var html=response.parse();title=html.title();
                html.select("script,style,noscript,nav,footer,header").remove();
                content=html.body().text();
            }
            if(content.isBlank()) throw new IllegalStateException("网页未包含可用文本");
            return new SearchGateway.Hit(url,title,content);
        }
        throw new IllegalStateException("网页重定向次数过多");
    }
}
