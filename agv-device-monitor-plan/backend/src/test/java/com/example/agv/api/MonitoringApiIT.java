package com.example.agv.api;

import com.example.agv.BackendApplication;
import com.example.agv.collector.TestDeviceServer;
import com.example.agv.monitoring.MonitorRepository;
import com.example.agv.monitoring.MonitorService;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MonitoringApiIT {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6"))
            .withDatabaseName("agv_api_test")
            .withUsername("u"+UUID.randomUUID().toString().substring(0,8)).withPassword(UUID.randomUUID().toString())
            .withCommand("--default-time-zone=+00:00").withStartupTimeout(Duration.ofSeconds(40)).withReuse(false)
            .withLabel("com.example.agv.test","monitor-api")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(3306))));
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private String base;

    @Test void servesRealHttpContractsAndCommittedSseSnapshots(CapturedOutput output) throws Exception {
        try (var server=new TestDeviceServer();ConfigurableApplicationContext app=start(server.port())) {
            base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
            var service=app.getBean(MonitorService.class);
            var repository=app.getBean(MonitorRepository.class);
            JsonNode devices=request("GET","/api/v1/devices",null,200);
            assertEquals(3,devices.get("total").asInt());
            JsonNode first=devices.get("items").get(0);
            String id=first.get("id").asText();
            assertTrue(first.get("id").isString());
            assertTrue(first.get("configRevision").isString());
            assertTrue(first.get("unitId").isIntegralNumber());
            assertTrue(first.get("enabled").isBoolean());
            JsonNode unknown=request("GET","/api/v1/devices/"+id+"/snapshot",null,200);
            assertTrue(unknown.get("heartbeat").isNull());
            assertFalse(unknown.has("updatedAt"));
            String newDevice="{\"deviceCode\":\"AGV-NEW\",\"name\":\"新设备\",\"host\":\"127.0.0.1\",\"port\":"+server.port()+",\"unitId\":4,\"enabled\":false}";
            JsonNode created=request("POST","/api/v1/devices",newDevice,201);
            assertEquals(4,created.get("unitId").asInt());
            assertEquals("DEVICE_CODE_EXISTS",request("POST","/api/v1/devices",newDevice,409).get("code").asText());
            request("POST","/api/v1/devices",newDevice.replace("AGV-NEW","AGV-OTHER"),409);
            request("POST","/api/v1/devices",newDevice.replace("127.0.0.1","unlisted.invalid"),400);
            request("PATCH","/api/v1/devices/"+id,"{\"unitId\":2}",400);
            request("PATCH","/api/v1/devices/"+id,"{}",400);
            request("GET","/api/v1/devices/99999",null,404);
            request("GET","/api/v1/devices?size=101",null,400);
            request("GET","/api/v1/devices/"+id+"/history?from=2026-09-01T00:00:00Z",null,400);
            request("GET","/api/v1/devices/"+id+"/history?from=2026-09-01T00:00:00Z&to=2026-09-09T00:00:00Z",null,400);
            request("GET","/api/v1/devices/"+id+"/history?from=2026-09-01T00:00:00Z&to=2026-09-02T00:00:00Z",null,200);

            server.battery.set(19); server.heartbeat.set(10); server.frozen.set(true);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            do { service.scan(); Thread.sleep(20); }
            while (((Number)repository.alarms(null,null,"ACTIVE",1,20).get("total")).intValue()<3 && System.nanoTime()<deadline);
            service.refreshView();
            JsonNode snapshot=request("GET","/api/v1/devices/"+id+"/snapshot",null,200);
            assertEquals("GOOD",snapshot.get("dataQuality").asText());
            assertEquals(1.2,snapshot.get("speedMps").asDouble());
            assertTrue(snapshot.get("lastFreshAt").asText().endsWith("Z"));
            // Fixed query fixtures: 使用隔离库的固定时间，验证左闭右开及同时间按ID稳定分页。
            JdbcTemplate jdbc=app.getBean(JdbcTemplate.class);
            var rangeStart=java.time.Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            for (int second : new int[]{0,0,1}) {
                var at=java.time.LocalDateTime.ofInstant(rangeStart.plusSeconds(second),java.time.ZoneOffset.UTC);
                jdbc.update("INSERT INTO device_sample_history (sample_key,device_id,sampled_at,run_state,battery_percent,"
                                +"speed_mm_s,position_code,target_code,fault_code,heartbeat,created_at) VALUES (?,?,?,'RUNNING',76,1200,3,7,0,1,?)",
                        UUID.randomUUID().toString(),Long.parseLong(id),at,at);
            }
            String range="/api/v1/devices/"+id+"/history?from="+rangeStart+"&to="+rangeStart.plusSeconds(1)+"&size=1";
            JsonNode page1=request("GET",range+"&page=1",null,200),page2=request("GET",range+"&page=2",null,200);
            assertEquals(2,page1.get("total").asInt());
            assertEquals(1,page1.get("items").size());
            assertTrue(Long.parseLong(page1.get("items").get(0).get("id").asText())>
                    Long.parseLong(page2.get("items").get(0).get("id").asText()));
            assertEquals(0,request("GET",range+"&page=3",null,200).get("items").size());
            assertEquals(0,request("GET",range+"&page=2147483647",null,200).get("items").size());
            request("GET",range+"&page=0",null,400);
            assertEquals(0,request("GET","/api/v1/devices/"+id+"/history?from=2025-12-30T00:00:00Z&to=2025-12-31T00:00:00Z",null,200).get("items").size());
            JsonNode alarms=request("GET","/api/v1/alarms?status=ACTIVE",null,200);
            assertEquals(3,alarms.get("total").asInt());
            JsonNode alarm=alarms.get("items").get(0);
            assertFalse(alarm.has("activeSlot"));
            String alarmId=alarm.get("id").asText();
            JsonNode ack=request("POST","/api/v1/alarms/"+alarmId+"/ack",null,200);
            assertEquals("ACTIVE",ack.get("status").asText());
            assertEquals(ack.get("acknowledgedAt"),request("POST","/api/v1/alarms/"+alarmId+"/ack",null,200).get("acknowledgedAt"));
            request("POST","/api/v1/alarms/"+alarmId+"/ack","{}",400);
            service.refreshView();
            var response=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/stream")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200,response.statusCode());
            assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("text/event-stream"));
            ExecutorService reader=Executors.newSingleThreadExecutor();
            try (var body=response.body();var lines=new BufferedReader(new InputStreamReader(body))) {
                Future<JsonNode> frame=reader.submit(() -> {
                    String line;
                    while ((line=lines.readLine())!=null) if (line.startsWith("data:")) return json.readTree(line.substring(5));
                    throw new AssertionError("SSE ended without snapshot");
                });
                JsonNode view=frame.get(3,TimeUnit.SECONDS);
                assertEquals("RUNNING",view.get("collectorStatus").asText());
                assertEquals(4,view.get("devices").size());
                assertEquals(3,view.get("activeAlarms").size());
                assertTrue(view.get("sequence").asLong()>0);
                assertTrue(view.get("devices").get(0).get("deviceId").isString());
                Future<Boolean> heartbeat=reader.submit(() -> {
                    String line;
                    while ((line=lines.readLine())!=null) if (line.startsWith(":heartbeat") || line.startsWith(": heartbeat")) return true;
                    return false;
                });
                assertTrue(heartbeat.get(2,TimeUnit.SECONDS));
            } finally { reader.shutdownNow(); }
            // Exercise capacity and cleanup: 旧连接关闭后可重新占满20个槽位，第21个明确返回503。
            List<java.io.InputStream> streams=new ArrayList<>();
            try {
                long retryUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while (streams.size()<20 && System.nanoTime()<retryUntil) {
                    var opened=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/stream")).timeout(Duration.ofSeconds(2)).build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (opened.statusCode()==200) streams.add(opened.body());
                    else { opened.body().close(); assertEquals(503,opened.statusCode()); Thread.sleep(20); }
                }
                assertEquals(20,streams.size());
                request("GET","/api/v1/stream",null,503);
            } finally { for (var stream:streams) stream.close(); }
        }
        assertFalse(output.getAll().contains("Failure in @ExceptionHandler"));
        assertFalse(output.getAll().contains("executor did not terminate"));
        assertFalse(output.getAll().contains("event executor terminated"));
    }
    private JsonNode request(String method,String path,String body,int status) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type","application/json").method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(status,response.statusCode(),response.body());
        JsonNode result=json.readTree(response.body());
        if (status>=400) { assertTrue(result.has("traceId")); assertTrue(result.has("fieldErrors")); }
        return result;
    }
    private ConfigurableApplicationContext start(int port) {
        // Override ambient configuration: 网络和数据库都指向本用例拥有的临时资源。
        return new SpringApplicationBuilder(BackendApplication.class).run(
                "--poll.enabled=false","--server.port=0","--server.address=127.0.0.1","--spring.profiles.active=demo",
                "--spring.datasource.url="+MYSQL.getJdbcUrl(),"--spring.datasource.username="+MYSQL.getUsername(),
                "--spring.datasource.password="+MYSQL.getPassword(),"--spring.flyway.url="+MYSQL.getJdbcUrl(),
                "--spring.flyway.user="+MYSQL.getUsername(),"--spring.flyway.password="+MYSQL.getPassword(),
                "--demo.seed-host=127.0.0.1","--demo.seed-port="+port,"--demo.allowed-hosts=127.0.0.1",
                "--demo.allowed-ports="+port,"--sse.heartbeat-ms=100");
    }
}
