package com.example.agv.api;

import com.example.agv.BackendApplication;
import com.example.agv.collector.TestDeviceServer;
import com.example.agv.monitoring.MonitorService;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class StreamBackpressureIT {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6"))
            .withDatabaseName("agv_stream_test").withUsername("u"+UUID.randomUUID().toString().substring(0,8))
            .withPassword(UUID.randomUUID().toString()).withCommand("--default-time-zone=+00:00")
            .withStartupTimeout(Duration.ofSeconds(40)).withReuse(false)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(3306))));

    @Test void slowTcpReadersCannotGrowQueuesOrStopCollection(CapturedOutput output) throws Exception {
        try (var wire=new TestDeviceServer();var app=new SpringApplicationBuilder(BackendApplication.class).run(
                "--server.address=127.0.0.1","--server.port=0","--spring.profiles.active=demo",
                "--spring.datasource.url="+MYSQL.getJdbcUrl(),"--spring.datasource.username="+MYSQL.getUsername(),
                "--spring.datasource.password="+MYSQL.getPassword(),"--spring.flyway.url="+MYSQL.getJdbcUrl(),
                "--spring.flyway.user="+MYSQL.getUsername(),"--spring.flyway.password="+MYSQL.getPassword(),
                "--demo.seed-host=127.0.0.1","--demo.seed-port="+wire.port(),"--demo.allowed-hosts=127.0.0.1",
                "--demo.allowed-ports="+wire.port(),"--sse.snapshot-ms=1","--sse.heartbeat-ms=100",
                "--poll.interval-ms=50","--server.tomcat.connection-timeout=1000")) {
            int port=((WebServerApplicationContext)app).getWebServer().getPort();
            var service=app.getBean(MonitorService.class);
            var metrics=app.getBean(MeterRegistry.class);
            for (int i=4;i<=10;i++) service.create("AGV-STREAM-"+i,"slow reader fixture "+i,"127.0.0.1",wire.port(),i,false);
            service.refreshView();
            List<Socket> slow=new ArrayList<>();
            int reads=wire.reads.get();
            try {
                for (int i=0;i<5;i++) {
                    Socket socket=new Socket(); socket.setReceiveBufferSize(512); socket.setSoTimeout(3000);
                    socket.connect(new InetSocketAddress("127.0.0.1",port),2000); slow.add(socket);
                    socket.getOutputStream().write(("GET /api/v1/stream HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    // Stop after headers: 真正停止读取TCP响应体，让内核窗口填满；不Mock SseEmitter。
                    StringBuilder headers=new StringBuilder();
                    while (!headers.toString().endsWith("\r\n\r\n") && headers.length()<8192) {
                        int next=socket.getInputStream().read(); assertNotEquals(-1,next); headers.append((char)next);
                    }
                    assertTrue(headers.toString().startsWith("HTTP/1.1 200"));
                }
                until(() -> metrics.find("agv.sse.frames.dropped").counter()!=null
                        && metrics.get("agv.sse.frames.dropped").counter().count()>0,15);
                assertTrue(wire.reads.get()>reads+3,"collector must continue while SSE writers are blocked");
                assertTrue(metrics.get("agv.sse.pending").gauge().value()<=40);
                assertTrue(metrics.get("agv.sse.senders.active").gauge().value()<=20);
                assertTrue(metrics.get("agv.poll.queue.size").gauge().value()<=16);
            } finally { for (Socket socket:slow) socket.close(); }
            until(() -> metrics.get("agv.sse.connections").gauge().value()==0
                    && metrics.get("agv.sse.senders.active").gauge().value()==0,8);

            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var json=JsonMapper.builder().findAndAddModules().build();
            ExecutorService readers=Executors.newSingleThreadExecutor();
            try {
                for (int cycle=0;cycle<10;cycle++) {
                    var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/stream")).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofInputStream());
                    assertEquals(200,response.statusCode());
                    try (var body=response.body();var lines=new BufferedReader(new InputStreamReader(body,StandardCharsets.UTF_8))) {
                        Future<?> check=readers.submit(() -> {
                            long last=0; String epoch=null; int frames=0;
                            try {
                                String line;
                                while (frames<10 && (line=lines.readLine())!=null) {
                                    if (!line.startsWith("data:")) continue;
                                    var frame=json.readTree(line.substring(5));
                                    long sequence=frame.get("sequence").asLong();
                                    assertTrue(sequence>last); last=sequence;
                                    if (epoch==null) epoch=frame.get("streamEpoch").asText();
                                    assertEquals(epoch,frame.get("streamEpoch").asText());
                                    assertEquals(10,frame.get("devices").size()); frames++;
                                }
                                assertEquals(10,frames);
                            } catch (IOException failure) { throw new UncheckedIOException(failure); }
                        });
                        check.get(3,TimeUnit.SECONDS);
                    }
                }
            } finally { readers.shutdownNow(); assertTrue(readers.awaitTermination(2,TimeUnit.SECONDS)); }
            until(() -> metrics.get("agv.sse.connections").gauge().value()==0
                    && metrics.get("agv.sse.senders.active").gauge().value()==0,8);
            assertEquals(0,metrics.get("agv.sse.pending").gauge().value());
            assertTrue(metrics.get("agv.sse.closed").counter().count()>=15);
        }
        assertFalse(output.getAll().contains("executor did not terminate"));
        assertFalse(output.getAll().contains("event executor terminated"));
        assertFalse(output.getAll().contains("Failure in @ExceptionHandler"));
    }
    private static void until(BooleanSupplier ready,int seconds) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while (!ready.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue(ready.getAsBoolean(),"Expected bounded stream lifecycle state");
    }
}
