package io.researchrunner.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.researchrunner.infrastructure.HistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

/** Contract tests for the two public routes mirrored from the Python service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicControllerTest {
    @Autowired
    private TestRestTemplate http;

    @MockitoBean
    private HistoryMapper mapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @Test
    void rootReturnsStableStatusEnvelope() {
        var response = http.getForEntity("/", String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("{\"service\":\"researchrunner-api\",\"status\":\"ok\",\"health\":\"/health/ready\"}", response.getBody());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void agentDiscoveryAdvertisesTheFourServices() {
        var response = http.getForEntity("/.well-known/agent-discovery.json", String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("*", response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        String body = response.getBody();
        assertTrue(body.contains("\"agent_discovery_version\":\"0.1\""), body);
        assertTrue(body.contains("\"name\":\"research\""), body);
        assertTrue(body.contains("\"name\":\"reports\""), body);
        assertTrue(body.contains("\"name\":\"chat\""), body);
        assertTrue(body.contains("\"name\":\"research_stream\""), body);
        assertTrue(body.contains("\"endpoint\":\"http://localhost:"), body);
        assertTrue(body.contains("/ws\""), body);
    }
}
