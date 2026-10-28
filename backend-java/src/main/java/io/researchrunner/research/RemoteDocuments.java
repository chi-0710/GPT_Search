package io.researchrunner.research;

import io.researchrunner.document.DocumentParser;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A2-03: fetch user-supplied document URLs and parse them with the same JVM parsers as uploads.
 *
 * <p>Mirrors Python's {@code OnlineDocumentLoader}: GET with a browser UA, six-second timeout,
 * extension-based parser, empty results fail the run. Adds SSRF guards: only http(s), no
 * loopback/private/link-local hosts, a redirect target is re-checked, and the body is capped.
 */
public final class RemoteDocuments {
    private final Duration timeout;
    private final int maxBytes;
    private final HttpClient http;

    public RemoteDocuments(Duration timeout, int maxBytes) {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("远程文档超时无效");
        if (maxBytes < 1) throw new IllegalArgumentException("远程文档大小上限无效");
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public List<Map<String, Object>> fetch(List<String> urls) throws Exception {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (String raw : urls) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("研究已取消");
            URI uri = parse(raw);
            String filename = filename(uri);
            String extension = extension(filename);
            if (!DocumentParser.FORMATS.contains(extension)) {
                throw new IllegalArgumentException("远程文档格式不受支持：" + raw);
            }
            var request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            URI finalUri = response.uri();
            if (!isPublicHttp(finalUri)) throw new IllegalArgumentException("远程文档地址不允许访问：" + raw);
            if (response.statusCode() != 200) throw new IllegalArgumentException("远程文档下载失败：" + raw);
            byte[] bytes = response.body();
            if (bytes == null) throw new IllegalArgumentException("远程文档内容为空：" + raw);
            if (bytes.length > maxBytes) throw new IllegalArgumentException("远程文档超过大小上限：" + raw);
            List<DocumentParser.Part> parts = new DocumentParser().parse(filename, bytes);
            for (DocumentParser.Part part : parts) {
                String content = part.content() == null ? "" : part.content().strip();
                if (content.isBlank()) continue;
                String title = part.page() == null ? filename : filename + " 第 " + part.page() + " 页";
                var item = new java.util.LinkedHashMap<String, Object>();
                item.put("url", raw);
                item.put("title", title);
                item.put("raw_content", content);
                if (part.page() != null) item.put("page", part.page());
                documents.add(item);
            }
        }
        if (documents.isEmpty()) throw new IllegalArgumentException("远程文档无法解析出可用内容");
        return documents;
    }

    private static URI parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("远程文档地址为空");
        URI uri;
        try {
            uri = URI.create(raw.strip());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("远程文档地址无效：" + raw);
        }
        if (!isPublicHttp(uri)) throw new IllegalArgumentException("远程文档地址不允许访问：" + raw);
        return uri;
    }

    static boolean isPublicHttp(URI uri) {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return false;
        String host = uri.getHost();
        if (host == null || host.isBlank()) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.equals("metadata.google.internal") || lower.equals("169.254.169.254")) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
            }
        } catch (Exception unresolved) {
            return false;
        }
        return true;
    }

    private static String filename(URI uri) {
        String path = uri.getPath();
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash < 0 ? "document" : path.substring(slash + 1);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
