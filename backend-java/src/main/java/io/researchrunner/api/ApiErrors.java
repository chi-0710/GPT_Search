package io.researchrunner.api;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String,String>> oversized(Exception exception) {
        return ResponseEntity.status(413).body(Map.of("detail","上传的文件过大"));
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(Exception exception) {
        return ResponseEntity.status(409).body(Map.of("detail", "The write conflicts with existing stored data"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> storage(DataAccessException exception) {
        return ResponseEntity.status(503).body(Map.of("detail", "PostgreSQL 存储服务不可用"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("detail", exception.getReason() == null ? "请求失败" : exception.getReason()));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> validation(Exception exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", "请求参数无效"));
    }
}
