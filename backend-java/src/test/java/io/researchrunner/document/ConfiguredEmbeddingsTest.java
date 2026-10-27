package io.researchrunner.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredEmbeddingsTest {
    @Test void qwenCompatibleClientBatchesTextsAndChecksDimensions() throws Exception {
        var requests=new AtomicInteger();var json=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/compatible-mode/v1/embeddings",exchange->{
            requests.incrementAndGet();
            assertEquals("Bearer synthetic",exchange.getRequestHeaders().getFirst("Authorization"));
            var request=json.readTree(exchange.getRequestBody());
            assertEquals("text-embedding-v4",request.path("model").asText());
            assertEquals(3,request.path("dimensions").asInt());
            var response=json.createObjectNode().put("object","list").put("model","text-embedding-v4");
            var data=response.putArray("data");
            for(int i=0;i<request.path("input").size();i++) {
                var entry=data.addObject().put("object","embedding").put("index",i);
                entry.putArray("embedding").add(1).add(0).add(0);
            }
            response.putObject("usage").put("prompt_tokens",2).put("total_tokens",2);
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var environment=new MockEnvironment().withProperty("EMBEDDING","dashscope:text-embedding-v4")
                    .withProperty("EMBEDDING_DIMENSION","3").withProperty("EMBEDDING_KWARGS","{\"chunk_size\":2,\"dimensions\":3}")
                    .withProperty("DASHSCOPE_API_KEY","synthetic")
                    .withProperty("DASHSCOPE_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort()+"/compatible-mode/v1");
            var embeddings=new ConfiguredEmbeddings(environment);
            var result=embeddings.embed(List.of("第一段","第二段","第三段"));
            assertEquals(3,result.size());assertEquals(2,requests.get());assertArrayEquals(new float[]{1,0,0},result.getFirst());
            environment.withProperty("EMBEDDING_KWARGS","{\"dimensions\":4}");
            assertThrows(IllegalArgumentException.class,()->embeddings.embed(List.of("资料")));assertEquals(2,requests.get());
        } finally {server.stop(0);}
    }

    @Test void unsupportedOptionsAndInvalidVectorsAreExplicitFailures() {
        var environment=new MockEnvironment().withProperty("EMBEDDING_KWARGS","{\"unknown\":true}");
        assertThrows(IllegalArgumentException.class,()->new ConfiguredEmbeddings(environment).embed(List.of("text")));
        assertThrows(IllegalArgumentException.class,()->DocumentStore.vector(new float[]{Float.NaN},1));
        assertThrows(IllegalArgumentException.class,()->DocumentStore.vector(new float[]{0,0},2));
    }
}
