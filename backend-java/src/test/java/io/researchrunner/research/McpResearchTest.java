package io.researchrunner.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class McpResearchTest {
    @TempDir Path temporary;
    @Test void realStdioClientDiscoversCallsAndClosesJavaProcess() throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var config=McpResearch.parse(List.of(Map.of("name","fixture","command",executable,"args",List.of("-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),McpStdioFixture.class.getName()))),
                new MockEnvironment().withProperty("JAVA_MCP_ALLOWED_COMMANDS",executable));
        try(var client=new McpResearch(new ObjectMapper(),Duration.ofSeconds(10),List.of())) {
            client.connect(config);
            var rows=client.collect("问题",(stage,prompt,input)->"{\"calls\":[{\"name\":\"fixture::lookup\",\"arguments\":{\"query\":\"问题\"}}]}");
            assertEquals("真实 MCP 工具证据",rows.getFirst().get("raw_content"));assertEquals("mcp://fixture/lookup",rows.getFirst().get("url"));
        }
    }
    @Test void streamableHttpAuthenticatesAndRejectsOutOfScopePathsBeforeCalling() throws Exception {
        var json=new ObjectMapper();var calls=new AtomicInteger();var auth=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp",exchange->{
            if(!exchange.getRequestMethod().equals("POST")){exchange.sendResponseHeaders(405,-1);exchange.close();return;}
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));var request=json.readTree(exchange.getRequestBody());
            if(!request.has("id")){exchange.sendResponseHeaders(202,-1);exchange.close();return;}
            Object result=switch(request.path("method").asText()) {
                case "initialize" -> Map.of("protocolVersion",request.path("params").path("protocolVersion").asText(),"capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","fixture","version","1"));
                case "tools/list" -> Map.of("tools",List.of(Map.of("name","lookup","description","read","inputSchema",Map.of("type","object","properties",Map.of("query",Map.of("type","string"),"path",Map.of("type","string"))))));
                case "tools/call" -> {calls.incrementAndGet();yield Map.of("content",List.of(Map.of("type","text","text","HTTP 证据")),"isError",false);}
                default -> Map.of();
            };
            byte[] bytes=json.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try(var client=new McpResearch(json,Duration.ofSeconds(5),List.of())) {
            client.connect(McpResearch.parse(List.of(Map.of("name","fixture","connection_url","http://127.0.0.1:"+server.getAddress().getPort()+"/mcp","connection_token","synthetic")),new MockEnvironment()));
            assertThrows(IllegalArgumentException.class,()->client.collect("问题",(s,p,i)->json.writeValueAsString(Map.of("calls",List.of(Map.of("name","fixture::lookup","arguments",Map.of("path",temporary.toString())))))));
            assertEquals(0,calls.get());
            var rows=client.collect("问题",(s,p,i)->"{\"calls\":[{\"name\":\"fixture::lookup\",\"arguments\":{\"query\":\"问题\"}}]}");
            assertEquals(1,calls.get());assertEquals("HTTP 证据",rows.getFirst().get("raw_content"));
            assertTrue(auth.stream().allMatch("Bearer synthetic"::equals));
        }finally{server.stop(0);}
    }
    @Test void pythonAndUnapprovedCommandsAreRejectedBeforeProcessStart() {
        for(String command:List.of("python","uvx","npx"))
            assertThrows(IllegalArgumentException.class,()->McpResearch.parse(List.of(Map.of("command",command)),new MockEnvironment().withProperty("JAVA_MCP_ALLOWED_COMMANDS","python,uvx")));
    }
    @Test void deepStrategyRunsMultipleRoundsWhileFastRunsOnce() throws Exception {
        var json=new ObjectMapper();var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp",exchange->{
            if(!exchange.getRequestMethod().equals("POST")){exchange.sendResponseHeaders(405,-1);exchange.close();return;}
            var request=json.readTree(exchange.getRequestBody());
            if(!request.has("id")){exchange.sendResponseHeaders(202,-1);exchange.close();return;}
            Object result=switch(request.path("method").asText()) {
                case "initialize" -> Map.of("protocolVersion",request.path("params").path("protocolVersion").asText(),"capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","fixture","version","1"));
                case "tools/list" -> Map.of("tools",List.of(Map.of("name","lookup","description","read","inputSchema",Map.of("type","object","properties",Map.of("query",Map.of("type","string"))))));
                case "tools/call" -> {calls.incrementAndGet();yield Map.of("content",List.of(Map.of("type","text","text","深挖证据 "+calls.get())),"isError",false);}
                default -> Map.of();
            };
            byte[] bytes=json.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/mcp";
            var selections=new AtomicInteger();
            ResearchNodes.Model model=(stage,prompt,input)->selections.incrementAndGet()<=2
                    ?"{\"calls\":[{\"name\":\"fixture::lookup\",\"arguments\":{\"query\":\"问题\"}}]}"
                    :"{\"calls\":[]}";
            try(var client=new McpResearch(json,Duration.ofSeconds(5),List.of(),"deep",3)) {
                client.connect(McpResearch.parse(List.of(Map.of("name","fixture","connection_url",url)),new MockEnvironment()));
                var rows=client.collect("问题",model);
                assertEquals(2,calls.get(),"deep 应执行两轮工具调用");
                assertEquals(2,rows.size());
            }
            calls.set(0);selections.set(0);
            try(var client=new McpResearch(json,Duration.ofSeconds(5),List.of(),"fast",3)) {
                client.connect(McpResearch.parse(List.of(Map.of("name","fixture","connection_url",url)),new MockEnvironment()));
                client.collect("问题",model);
                assertEquals(1,calls.get(),"fast 只执行一轮");
            }
        } finally {server.stop(0);}
    }

    @Test void timeoutAndCancellationReleaseStdioProcesses() throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        for(boolean cancel:List.of(false,true)) {
            Path pidFile=temporary.resolve("pid-"+cancel);
            var config=McpResearch.parse(List.of(Map.of("name","fixture","command",executable,"args",List.of("-cp",
                    System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),McpStdioFixture.class.getName(),pidFile.toString()))),
                    new MockEnvironment().withProperty("JAVA_MCP_ALLOWED_COMMANDS",executable));
            var client=new McpResearch(new ObjectMapper(),Duration.ofSeconds(10),List.of());
            try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                client.connect(config);
                var future=executor.submit(()->client.collect("等待",(s,p,i)->"{\"calls\":[{\"name\":\"fixture::lookup\",\"arguments\":{\"query\":\"wait\"}}]}"));
                if(cancel){
                    long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();
                    while(!java.nio.file.Files.exists(Path.of(pidFile+".called")) && System.nanoTime()<deadline)Thread.sleep(20);
                    assertTrue(java.nio.file.Files.exists(Path.of(pidFile+".called")),"Cancel a genuinely in-flight tool request");
                    future.cancel(true);assertThrows(java.util.concurrent.CancellationException.class,future::get);
                }
                else assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(18,java.util.concurrent.TimeUnit.SECONDS));
                client.close();
            } finally {client.close();}
            long pid=Long.parseLong(java.nio.file.Files.readString(pidFile));
            if(ProcessHandle.of(pid).isPresent())ProcessHandle.of(pid).orElseThrow().onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive());
        }
    }
}
