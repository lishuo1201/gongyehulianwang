package com.example.agv.simulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = "simulator.modbus.port=0")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SimulatorApplicationIT {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private SimulatorModbusServer modbus;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void resetOnlyThisApplication() throws Exception {
        assertEquals(200, request("POST", "/sim/v1/reset", "").statusCode());
    }

    @Test
    void servesHealthOverHttp() throws Exception {
        // Check real HTTP: 验证实际应用入口；协议服务在本测试绑定独立临时端口。
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("UP", jsonMapper.readTree(response.body()).path("status").asString());
    }

    @Test
    void managesSilentUnitAndRecoversItWhileOtherUnitsStillRespond() throws Exception {
        assertEquals(200, request("PUT", "/sim/v1/units/2/scenario", "{\"mode\":\"SILENT\"}").statusCode());
        var units = jsonMapper.readTree(request("GET", "/sim/v1/units", "").body());
        assertEquals(3, units.size());
        assertEquals("SILENT", units.get(1).path("mode").asString());
        try (var wire = new SimulatorModbusIT.WireClient(modbus.port())) {
            wire.send(2, SimulatorModbusIT.hex("03 00 00 00 08"));
            assertEquals(76, wire.read(1)[2]);
            assertEquals(88, wire.read(3)[2]);
            wire.assertNoResponse();
            assertEquals(200, request("PUT", "/sim/v1/units/2/scenario", "{\"mode\":\"NORMAL\"}").statusCode());
            assertEquals(23, wire.read(2)[2]);
        }
    }

    @Test
    void switchesScenariosThroughHttpAndReadsTheirEffectsOverTcp() throws Exception {
        try (var wire = new SimulatorModbusIT.WireClient(modbus.port())) {
            assertEquals(200, request("PUT", "/sim/v1/units/1/scenario", "{\"mode\":\"LOW_BATTERY\"}").statusCode());
            assertEquals(19, wire.read(1)[2]);
            assertEquals(200, request("PUT", "/sim/v1/units/1/scenario", "{\"mode\":\"FAULT\"}").statusCode());
            assertEquals(200, request("PUT", "/sim/v1/units/1/scenario", "{\"mode\":\"FROZEN\"}").statusCode());
            int[] frozen = wire.read(1);
            assertEquals(3, frozen[1]);
            assertEquals(0, frozen[3]);
            assertEquals(2, frozen[6]);
            assertEquals(200, request("PUT", "/sim/v1/units/1/scenario", "{\"mode\":\"INVALID\"}").statusCode());
            assertEquals(101, wire.read(1)[2]);
            assertEquals(200, request("PUT", "/sim/v1/units/1/scenario", "{\"mode\":\"FROZEN\"}").statusCode());
            assertArrayEquals(frozen, wire.read(1));
            assertEquals(200, request("POST", "/sim/v1/reset", "").statusCode());
            int[] normal = wire.read(1);
            assertEquals(76, normal[2]);
            assertEquals(1, normal[1]);
            assertEquals(1200, normal[3]);
            assertEquals(0, normal[6]);
        }
    }

    @Test
    void rejectsInvalidManagementInputsWithoutChangingTheDevice() throws Exception {
        assertEquals(200, request("PUT", "/sim/v1/units/1/scenario", "{\"mode\":\"FROZEN\"}").statusCode());
        try (var wire = new SimulatorModbusIT.WireClient(modbus.port())) {
            int[] before = wire.read(1);
            for (String body : new String[] {"{}", "null", "[]", "{", "{\"mode\":null}",
                    "{\"mode\":1}", "{\"mode\":\"normal\"}", "{\"mode\":\"UNKNOWN\"}",
                    "{\"mode\":\"NORMAL\",\"extra\":true}"}) {
                assertEquals(400, request("PUT", "/sim/v1/units/1/scenario", body).statusCode(), body);
            }
            for (String id : new String[] {"0", "4", "-1", "abc"}) {
                assertEquals(400, request("PUT", "/sim/v1/units/" + id + "/scenario", "{\"mode\":\"NORMAL\"}").statusCode());
            }
            assertArrayEquals(before, wire.read(1));
        }
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
