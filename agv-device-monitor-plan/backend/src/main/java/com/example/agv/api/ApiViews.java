package com.example.agv.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Explicit output contract：T09/T10，REST与已提交SSE视图共用显式DTO，避免数据库增加列就自动暴露接口。
 * 资源ID/revision为字符串，防止浏览器Number损失大整数精度；Unit ID是协议小整数，不随字段名含Id就转字符串。
 * 时间用Instant输出UTC，速度在Repository已换算为m/s；nullable测量必须保留null，不能默认成0。
 * 这些record描述输出，不运行设备状态判断，也不代表同一HTTP查询期间所有资源都被冻结不变。
 */
public final class ApiViews {
    private ApiViews() { }

    public record DeviceView(String id,String deviceCode,String name,String host,int port,int unitId,
                             boolean enabled,String configRevision,Instant createdAt,Instant updatedAt) {
        public static DeviceView from(Map<String,Object> row) {
            return new DeviceView(text(row,"id"),text(row,"deviceCode"),text(row,"name"),text(row,"host"),
                    integer(row,"port"),integer(row,"unitId"),(Boolean)row.get("enabled"),text(row,"configRevision"),
                    instant(row,"createdAt"),instant(row,"updatedAt"));
        }
    }
    // Three dimensions and two times：最近响应时间与最近有效样本时间并列输出，便于识别ONLINE但STALE/INVALID。
    public record Snapshot(String deviceId,String deviceCode,String connectionStatus,String dataQuality,String runState,
                           Integer batteryPercent,Double speedMps,Integer positionCode,Integer targetCode,Integer faultCode,
                           Integer heartbeat,Instant lastResponseAt,Instant lastFreshAt,String snapshotRevision) {
        public static Snapshot from(Map<String,Object> row) {
            return new Snapshot(text(row,"deviceId"),text(row,"deviceCode"),text(row,"connectionStatus"),text(row,"dataQuality"),
                    text(row,"runState"),integer(row,"batteryPercent"),decimal(row,"speedMps"),integer(row,"positionCode"),
                    integer(row,"targetCode"),integer(row,"faultCode"),integer(row,"heartbeat"),instant(row,"lastResponseAt"),
                    instant(row,"lastFreshAt"),text(row,"snapshotRevision"));
        }
    }
    // Stored measurements：历史记录已经通过合法性与新鲜度筛选；缺少某个时间点不等于那个时间读数为0。
    public record Sample(String id,String deviceId,Instant sampledAt,String runState,Integer batteryPercent,Double speedMps,
                         Integer positionCode,Integer targetCode,Integer faultCode,Integer heartbeat) {
        public static Sample from(Map<String,Object> row) {
            return new Sample(text(row,"id"),text(row,"deviceId"),instant(row,"sampledAt"),text(row,"runState"),
                    integer(row,"batteryPercent"),decimal(row,"speedMps"),integer(row,"positionCode"),integer(row,"targetCode"),
                    integer(row,"faultCode"),integer(row,"heartbeat"));
        }
    }
    // Observed transitions：事件记录后端看到的离散变化，不宣称还原轮询间所有物理状态变化。
    public record Event(String id,String deviceId,String eventType,String oldValue,String newValue,Instant occurredAt,String reason) {
        public static Event from(Map<String,Object> row) {
            return new Event(text(row,"id"),text(row,"deviceId"),text(row,"eventType"),text(row,"oldValue"),text(row,"newValue"),
                    instant(row,"occurredAt"),text(row,"reason"));
        }
    }
    // Separate acknowledgment and closure：acknowledgedAt只代表知悉；closedAt也可能源于SUPPRESSED，未必正常恢复。
    public record Alarm(String id,String deviceId,String deviceCode,String ruleCode,String severity,String status,
                        String triggerValue,String lastValue,Instant triggeredAt,Instant lastObservedAt,
                        Instant acknowledgedAt,Instant recoveredAt,Instant closedAt,String closeReason) {
        public static Alarm from(Map<String,Object> row) {
            return new Alarm(text(row,"id"),text(row,"deviceId"),text(row,"deviceCode"),text(row,"ruleCode"),
                    text(row,"severity"),text(row,"status"),text(row,"triggerValue"),text(row,"lastValue"),
                    instant(row,"triggeredAt"),instant(row,"lastObservedAt"),instant(row,"acknowledgedAt"),
                    instant(row,"recoveredAt"),instant(row,"closedAt"),text(row,"closeReason"));
        }
    }
    public record Page<T>(List<T> items,int page,int size,long total) {
        @SuppressWarnings("unchecked")
        public static <T> Page<T> from(Map<String,Object> row,Function<Map<String,Object>,T> mapper) {
            return new Page<>(((List<Map<String,Object>>)row.get("items")).stream().map(mapper).toList(),
                    integer(row,"page"),integer(row,"size"),((Number)row.get("total")).longValue());
        }
    }
    private static String text(Map<String,Object> row,String key) { return (String)row.get(key); }
    private static Instant instant(Map<String,Object> row,String key) { return (Instant)row.get(key); }
    private static Integer integer(Map<String,Object> row,String key) {
        return row.get(key)==null ? null : ((Number)row.get(key)).intValue();
    }
    private static Double decimal(Map<String,Object> row,String key) {
        return row.get(key)==null ? null : ((Number)row.get(key)).doubleValue();
    }
}
