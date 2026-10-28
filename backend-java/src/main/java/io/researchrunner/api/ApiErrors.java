package io.researchrunner.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> oversized(Exception exception) {
        return ResponseEntity.status(413).body(detail("上传的文件过大"));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(Exception exception) {
        return ResponseEntity.status(409).body(detail("The write conflicts with existing stored data"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> storage(DataAccessException exception) {
        return ResponseEntity.status(503).body(detail("PostgreSQL 存储服务不可用"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(detail(exception.getReason() == null ? "请求失败" : exception.getReason()));
    }

    @ExceptionHandler(io.researchrunner.research.UnsupportedProviderException.class)
    public ResponseEntity<Map<String, Object>> unsupportedProvider(
            io.researchrunner.research.UnsupportedProviderException exception) {
        return ResponseEntity.status(501).body(detail(exception.getMessage()));
    }

    /**
     * A2-01: produce FastAPI-compatible validation errors. Python returns
     * {@code {"detail":[{"type","loc","msg","input"}]}}; the previous Java implementation
     * returned a plain string, which broke clients that inspect the error list.
     */
    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> validation(Exception exception) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (exception instanceof HandlerMethodValidationException invalid) {
            invalid.getAllValidationResults().forEach(result -> {
                var parameter = result.getMethodParameter();
                String location = location(parameter.getParameter());
                String name = parameter.getParameterName() == null ? "parameter" : parameter.getParameterName();
                result.getResolvableErrors().forEach(error -> errors.add(error(
                        location, name, error.getDefaultMessage(), "value_error")));
            });
        } else if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            errors.add(error("query", mismatch.getName(), "输入值无效", "type_error"));
        } else if (exception instanceof MissingServletRequestParameterException missing) {
            errors.add(error("query", missing.getParameterName(), "缺少必填参数", "missing"));
        } else if (exception instanceof HttpMessageNotReadableException) {
            errors.add(error("body", "body", "JSON 解析失败", "json_invalid"));
        }
        if (errors.isEmpty()) {
            errors.add(error("body", "body", "请求参数无效", "value_error"));
        }
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", errors));
    }

    private static String location(java.lang.reflect.Parameter parameter) {
        if (parameter.isAnnotationPresent(PathVariable.class)) {
            return "path";
        }
        if (parameter.isAnnotationPresent(RequestBody.class)) {
            return "body";
        }
        if (parameter.isAnnotationPresent(RequestParam.class)) {
            return "query";
        }
        return "query";
    }

    private static Map<String, Object> error(String location, String name, String message, String type) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("loc", List.of(location, name));
        item.put("msg", message == null ? "请求参数无效" : message);
        return item;
    }

    private static Map<String, Object> detail(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", message);
        return body;
    }
}
