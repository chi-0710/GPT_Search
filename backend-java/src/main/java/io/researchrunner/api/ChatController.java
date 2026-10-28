package io.researchrunner.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.researchrunner.report.ReportChat;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="researchrunner.writes-enabled",havingValue="true")
public class ChatController {
    private final ReportChat chat;
    public ChatController(ReportChat chat){this.chat=chat;}
    @PostMapping("/api/chat") public Map<String,Object> chat(@RequestBody JsonNode request) throws Exception {return chat.chat(request);}
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> invalid() {
        return org.springframework.http.ResponseEntity.unprocessableEntity().body(Map.of("detail","聊天参数或模型配置无效"));
    }
}
