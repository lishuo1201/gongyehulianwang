package com.example.agv;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class BackendApplicationIT {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void servesHealthOverHttp() throws Exception {
        // Check real HTTP: 验证应用启动和 HTTP 链路，尚不代表数据库或采集器就绪。
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("UP", jsonMapper.readTree(response.body()).path("status").asString());
    }
}
