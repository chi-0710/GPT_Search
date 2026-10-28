package io.researchrunner.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** A6-04: error responses must not leak storage details or credentials. */
class ApiErrorsSecurityTest {
    private final ApiErrors errors = new ApiErrors();

    @Test
    void oversizedUploadReturns413WithoutInternalDetails() {
        var response = errors.oversized(new MaxUploadSizeExceededException(1024));
        assertEquals(413, response.getStatusCode().value());
        assertEquals("上传的文件过大", response.getBody().get("detail"));
    }

    @Test
    void storageFailureReturns503WithoutJdbcUrlOrDriverMessage() {
        var response = errors.storage(new DataAccessResourceFailureException(
                "jdbc:postgresql://user:secret@db/app connection refused"));
        assertEquals(503, response.getStatusCode().value());
        String detail = String.valueOf(response.getBody().get("detail"));
        assertFalse(detail.contains("jdbc:"), detail);
        assertFalse(detail.contains("secret"), detail);
        assertEquals("PostgreSQL 存储服务不可用", detail);
    }
}
