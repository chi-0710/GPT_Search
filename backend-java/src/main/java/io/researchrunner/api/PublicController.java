package io.researchrunner.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, database-free endpoints kept for frontend/monitoring compatibility.
 *
 * <p>Mirrors the routes that existed before the Python backend was retired. Kept outside the
 * writes-enabled condition because both endpoints are read-only and must work in the default
 * read-only deployment.
 */
@RestController
public class PublicController {
    private final String contact;

    public PublicController(@Value("${AGENT_DISCOVERY_CONTACT:}") String contact) {
        this.contact = contact;
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "researchrunner-api");
        body.put("status", "ok");
        body.put("health", "/health/ready");
        return body;
    }

    @GetMapping("/.well-known/agent-discovery.json")
    public ResponseEntity<Map<String, Object>> agentDiscovery(HttpServletRequest request) {
        String origin = origin(request);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("agent_discovery_version", "0.1");
        document.put("domain", request.getServerName());
        List<Map<String, Object>> services = new ArrayList<>();
        services.add(service("research", "Submit a research job and generate a report.",
                origin + "/report/", "http"));
        services.add(service("reports", "Create, list, and manage generated research reports.",
                origin + "/api/reports", "http"));
        services.add(service("chat", "Ask follow-up questions against a generated report.",
                origin + "/api/chat", "http"));
        services.add(service("research_stream", "Realtime WebSocket stream for interactive research runs.",
                websocketOrigin(origin) + "/ws", "websocket"));
        document.put("services", services);
        if (!contact.isBlank()) {
            document.put("contact", contact);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .body(document);
    }

    private static Map<String, Object> service(String name, String description, String endpoint, String protocol) {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("name", name);
        service.put("description", description);
        service.put("endpoint", endpoint);
        service.put("protocol", protocol);
        service.put("auth", "none");
        service.put("governance", "none");
        service.put("free_tier", true);
        return service;
    }

    /** Same shape as FastAPI's request.base_url: scheme, host and non-default port. */
    private static String origin(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    private static String websocketOrigin(String origin) {
        if (origin.startsWith("https://")) {
            return "wss://" + origin.substring("https://".length());
        }
        if (origin.startsWith("http://")) {
            return "ws://" + origin.substring("http://".length());
        }
        return origin;
    }
}
