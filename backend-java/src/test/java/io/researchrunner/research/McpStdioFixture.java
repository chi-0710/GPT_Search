package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

/** Offline test server, launched by the real Java SDK stdio client. */
public class McpStdioFixture {
    public static void main(String[] args) throws Exception {
        if(args.length>0)java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),Long.toString(ProcessHandle.current().pid()));
        var json=new ObjectMapper();var input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));
        var output=new java.io.OutputStreamWriter(System.out,java.nio.charset.StandardCharsets.UTF_8);String line;
        while((line=input.readLine())!=null) {
            var request=json.readTree(line);if(!request.has("id"))continue;
            Object result=switch(request.path("method").asText()) {
                case "initialize" -> Map.of("protocolVersion",request.path("params").path("protocolVersion").asText(),"capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","fixture","version","1"));
                case "tools/list" -> Map.of("tools",java.util.List.of(Map.of("name","lookup","description","Read evidence","inputSchema",Map.of("type","object","properties",Map.of("query",Map.of("type","string"))))));
                case "tools/call" -> {
                    if(args.length>0)java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]+".called"),"called");
                    if(request.path("params").path("arguments").path("query").asText().equals("wait"))Thread.sleep(30000);
                    yield Map.of("content",java.util.List.of(Map.of("type","text","text","真实 MCP 工具证据")),"isError",false);
                }
                default -> Map.of();
            };
            output.write(json.writeValueAsString(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result))+"\n");output.flush();
        }
    }
}
