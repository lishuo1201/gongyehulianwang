package com.example.agv.api;

import com.example.agv.monitoring.MonitorRepository;
import com.example.agv.monitoring.MonitorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * HTTP business boundary：T09，将设备台账、已提交快照、历史/事件和告警映射为稳定REST契约。
 * 查询走MonitorRepository，登记/启停/确认走MonitorService；这里不读取Modbus、不直接操纵模拟场景。
 * <p>Validate before work：严格检查字段名、JSON类型、范围及时间窗口，错误交给ApiErrors返回对应状态码。
 * 设备测量字段不允许由页面PATCH，避免把“监控展示”变成“手工修改设备读数”。
 * Evidence：MonitoringApiIT通过真实HTTP校验数字/字符串类型、分页时间边界、400/404/409/503及SSE。
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="monitor.enabled",matchIfMissing=true)
public class MonitorController {
    private final MonitorRepository repository;
    private final MonitorService service;
    public MonitorController(MonitorRepository repository,MonitorService service) { this.repository=repository; this.service=service; }

    @GetMapping("/devices") public Object devices(@RequestParam Map<String,String> query) {
        keys(query,Set.of("enabled","page","size"));
        Boolean enabled=null;
        if (query.containsKey("enabled")) {
            if (!Set.of("true","false").contains(query.get("enabled"))) throw invalid("enabled 必须为 true/false");
            enabled=Boolean.valueOf(query.get("enabled"));
        }
        return ApiViews.Page.from(repository.devicesPage(enabled,page(query),size(query)),ApiViews.DeviceView::from);
    }
    @GetMapping("/devices/{id}") public Object device(@PathVariable String id) { return ApiViews.DeviceView.from(repository.deviceView(requireDevice(id))); }
    @GetMapping("/devices/{id}/snapshot") public Object snapshot(@PathVariable String id) { return ApiViews.Snapshot.from(repository.snapshot(number(id))); }

    // Register the read target：deviceCode仅业务标识，host/port/unitId才决定读谁；service还会检查目标允许列表。
    // Strict JSON types：unitId应为整数2，不接受字符串"2"或浮点2.0自动转换，避免模糊配置。
    @PostMapping("/devices") public ResponseEntity<?> create(@RequestBody Map<String,Object> body) {
        keys(body,Set.of("deviceCode","name","host","port","unitId","enabled"));
        String code=text(body,"deviceCode",32);
        if (!code.equals(body.get("deviceCode")) || !code.matches("[A-Z0-9-]{1,32}")) throw invalid("deviceCode 格式不合法");
        String name=text(body,"name",64),host=text(body,"host",128);
        if (!host.equals(body.get("host"))) throw invalid("host 不允许前后空格");
        int port=integer(body,"port",65535),unit=integer(body,"unitId",247);
        if (!(body.get("enabled") instanceof Boolean enabled)) throw invalid("enabled 必须为布尔值");
        Map<String,Object> result=service.create(code,name,host,port,unit,enabled);
        return ResponseEntity.created(URI.create("/api/v1/devices/"+result.get("id"))).body(ApiViews.DeviceView.from(result));
    }
    // Preserve historical identity：只允许name/enabled，拒绝改host/port/unitId及deviceCode，旧历史不会被重新归属。
    @PatchMapping("/devices/{id}") public Object patch(@PathVariable String id,@RequestBody Map<String,Object> body) {
        keys(body,Set.of("name","enabled"));
        if (body.isEmpty()) throw invalid("修改内容不能为空");
        String name=body.containsKey("name") ? text(body,"name",64) : null;
        Boolean enabled=null;
        if (body.containsKey("enabled")) {
            if (!(body.get("enabled") instanceof Boolean value)) throw invalid("enabled 必须为布尔值");
            enabled=value;
        }
        return ApiViews.DeviceView.from(service.patch(number(id),name,enabled));
    }
    @GetMapping("/devices/{id}/history") public Object history(@PathVariable String id,@RequestParam Map<String,String> query) {
        return timeline(id,false,query);
    }
    @GetMapping("/devices/{id}/events") public Object events(@PathVariable String id,@RequestParam Map<String,String> query) {
        return timeline(id,true,query);
    }
    /**
     * Bound the query window：默认过去24小时，一次最多查7天；这与历史保留天数是不同概念。
     * events选择离散变化，history选择已保存测量，二者不能互相补出没有观察到的数据。
     * from/to按UTC解析，SQL采用左闭右开；页面负责把用户本地输入时间转换成ISO时间。
     */
    private Object timeline(String id,boolean events,Map<String,String> query) {
        keys(query,Set.of("from","to","page","size"));
        if (query.containsKey("from")!=query.containsKey("to")) throw invalid("from/to 必须同时提供");
        Instant to=query.containsKey("to") ? parseTime(query.get("to")) : Instant.now();
        Instant from=query.containsKey("from") ? parseTime(query.get("from")) : to.minus(Duration.ofHours(24));
        if (!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(7))>0) throw invalid("时间范围应在7天以内，且 from < to");
        var result=repository.timeline(number(id),events,from,to,page(query),size(query));
        return events ? ApiViews.Page.from(result,ApiViews.Event::from) : ApiViews.Page.from(result,ApiViews.Sample::from);
    }
    @GetMapping("/alarms") public Object alarms(@RequestParam Map<String,String> query) {
        keys(query,Set.of("deviceId","ruleCode","status","page","size"));
        String rule=query.get("ruleCode"),status=query.get("status");
        if (rule!=null && !Set.of("LOW_BATTERY","DEVICE_FAULT","DEVICE_OFFLINE","DATA_STALE").contains(rule)) throw invalid("ruleCode 不合法");
        if (status!=null && !Set.of("ACTIVE","RECOVERED","SUPPRESSED").contains(status)) throw invalid("status 不合法");
        return ApiViews.Page.from(repository.alarms(query.containsKey("deviceId") ? number(query.get("deviceId")) : null,
                rule,status,page(query),size(query)),ApiViews.Alarm::from);
    }
    // Acknowledge, do not recover：接口没有status/recoveredAt请求字段，恢复必须由后续设备观测驱动规则。
    @PostMapping("/alarms/{id}/ack") public Object ack(@PathVariable String id,@RequestBody(required=false) String body) {
        if (body!=null && !body.isBlank()) throw invalid("确认接口不接受请求体");
        return ApiViews.Alarm.from(service.ack(number(id)));
    }
    private long requireDevice(String id) { long value=number(id); repository.device(value); return value; }
    private static long number(String text) {
        if (text==null || !text.matches("[1-9][0-9]*")) throw invalid("ID 或分页必须为正整数");
        try { return Long.parseLong(text); } catch (NumberFormatException error) { throw invalid("数值超出范围"); }
    }
    private static int page(Map<String,String> query) {
        long value=number(query.getOrDefault("page","1"));
        if (value>Integer.MAX_VALUE) throw invalid("page 超出范围");
        return (int)value;
    }
    private static int size(Map<String,String> query) {
        long value=number(query.getOrDefault("size","20"));
        if (value>100) throw invalid("size 最大100");
        return (int)value;
    }
    private static int integer(Map<String,Object> body,String key,int max) {
        Object raw=body.get(key);
        if (!(raw instanceof Integer) && !(raw instanceof Long)) throw invalid(key+" 必须为整数");
        long value=((Number)raw).longValue();
        if (value<1 || value>max) throw invalid(key+" 超出范围");
        return (int)value;
    }
    private static String text(Map<String,Object> body,String key,int max) {
        if (!(body.get(key) instanceof String raw)) throw invalid(key+" 必须为字符串");
        String value=raw.strip();
        if (value.isEmpty() || value.length()>max) throw invalid(key+" 长度不合法");
        return value;
    }
    private static Instant parseTime(String value) {
        try { return Instant.parse(value); } catch (java.time.DateTimeException invalidTime) { throw invalid("时间应为 ISO 8601 UTC"); }
    }
    // Reject unknown intent：拼错字段或试图修改不开放的属性必须明确400，不忽略后返回假成功。
    private static void keys(Map<String,?> body,Set<String> allowed) {
        if (body==null || !allowed.containsAll(body.keySet())) throw invalid("包含未定义字段");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
