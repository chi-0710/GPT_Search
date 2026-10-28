package io.researchrunner;

import io.researchrunner.infrastructure.HistoryMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Starts the actual Spring application with only database calls replaced. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationHttpTest {
    @Autowired
    private TestRestTemplate http;
    @MockitoBean
    private HistoryMapper mapper;
    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @Test
    void applicationStartsAndServesExistingReportEnvelope() {
        when(mapper.reports(isNull(), eq(50), isNull(), eq(false))).thenReturn(List.of());
        var response = http.getForEntity("/api/reports", String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("{\"reports\":[],\"next_cursor\":null}", response.getBody());
    }

    @Test
    void validationIsActiveInTheRealApplication() {
        var response = http.getForEntity("/api/reports?limit=201", String.class);
        assertEquals(422, response.getStatusCode().value());
        verifyNoInteractions(mapper);
    }

    @Test
    void validationUsesFastApiCompatibleDetailArray() {
        var response = http.getForEntity("/api/reports?limit=not-a-number", String.class);
        assertEquals(422, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(body.contains("\"detail\":["), body);
        assertTrue(body.contains("\"loc\":[\"query\",\"limit\"]"), body);
        assertTrue(body.contains("\"msg\":"), body);
    }
}
