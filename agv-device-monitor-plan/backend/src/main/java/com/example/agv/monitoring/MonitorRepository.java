package com.example.agv.monitoring;

import com.example.agv.protocol.DeviceSample;
import com.example.agv.protocol.RunState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

/**
 * Store observed facts：T02/T07/T08/T15，五张表分别回答不同的工业监控问题。
 * device是“读谁、是否启用”；device_snapshot是“最近已提交什么”；device_sample_history是低频趋势；
 * device_state_event是离散状态变化；alarm是一轮异常的触发、确认、恢复或抑制过程。
 * <p>One business transaction：write依次写快照、事件、应保存的历史和告警；任一失败全部回滚。
 * 网络读取不在本类发生，业务内存的替换由MonitorService在事务成功返回后完成。
 * <p>Schema source：表结构与约束见V1__create_monitor_tables.sql及docs/03-data-model.md。
 * 已应用迁移不可为了加注释而改写校验值；这里说明调用与SQL语义，结构演进应另建迁移。
 * Evidence：MonitoringPersistenceIT、P1MonitoringIT使用独立真实MySQL验证事务及保留边界。
 */
public final class MonitorRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MonitorSettings settings;

    public MonitorRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, MonitorSettings settings) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(5);
        this.settings = settings;
    }

    /**
     * Retry a whole unit of work：捕获点在TransactionTemplate.execute外，确保旧事务已完整回滚。
     * 只有uk_alarm_active竞争最多重试一次，重试重新读取数据库中的活动告警；其他约束冲突继续抛出。
     * Call at the outer boundary：调用方用此方法包住完整业务单元；内部write直接加入该事务，
     * 不在回滚中的事务内部捕获插入异常后继续SQL，也不嵌套调用本方法期待得到新事务。
     */
    public <T> T transaction(Supplier<T> work) {
        // Retry after rollback: 只在最外层事务完整结束后重试已识别的活动槽冲突。
        for (int attempt=0; ; attempt++) {
            try { return transactions.execute(status -> work.get()); }
            catch (DuplicateKeyException conflict) {
                if (attempt >= 1 || !constraint(conflict,"uk_alarm_active")) throw conflict;
            }
        }
    }

    public static boolean constraint(DuplicateKeyException conflict,String name) {
        Throwable cause=conflict.getMostSpecificCause();
        String message=cause.getMessage();
        return message!=null && (message.contains("'"+name+"'") || message.contains("."+name+"'"));
    }

    public List<Device> devices() { return jdbc.query("SELECT * FROM device ORDER BY id", (rs,n) -> device(rs)); }

    public Device device(long id) {
        var found = jdbc.query("SELECT * FROM device WHERE id=?", (rs,n) -> device(rs),id);
        if (found.isEmpty()) throw new NoSuchElementException("设备不存在");
        return found.get(0);
    }

    private static Device device(ResultSet rs) throws SQLException {
        return new Device(rs.getLong("id"),rs.getString("device_code"),rs.getString("name"),
                rs.getString("host"),rs.getInt("port"),rs.getInt("unit_id"),rs.getBoolean("enabled"),
                rs.getLong("config_revision"),instant(rs,"created_at"),instant(rs,"updated_at"));
    }

    private static Instant instant(ResultSet rs,String column) throws SQLException {
        LocalDateTime value = rs.getObject(column,LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /**
     * Load values, recheck liveness：从数据库恢复最后可信测量及展示时间，但不恢复旧ONLINE和单调时间基准。
     * nullable heartbeat是这里判断整组历史测量是否存在的标志，0仍是合法已测值。
     * 即使保存着旧样本，DeviceState构造器也会让质量先为STALE，等待本进程的新观测。
     */
    public DeviceState initialState(Device device,long now) {
        return jdbc.queryForObject("SELECT * FROM device_snapshot WHERE device_id=?", (rs,n) -> {
            DeviceSample sample=rs.getObject("heartbeat") == null ? null : new DeviceSample(1,RunState.valueOf(rs.getString("run_state")),
                        rs.getInt("battery_percent"),rs.getInt("speed_mm_s"),rs.getInt("position_code"),
                        rs.getInt("target_code"),rs.getInt("fault_code"),rs.getInt("heartbeat"));
            DeviceState state=new DeviceState(now,device.enabled(),sample);
            state.lastResponseAt=instant(rs,"last_response_at");
            state.lastFreshAt=instant(rs,"last_fresh_at");
            return state;
        },device.id());
    }

    public Map<String,Object> snapshot(long id) {
        var rows = rows("SELECT s.*,d.device_code FROM device_snapshot s JOIN device d ON d.id=s.device_id WHERE s.device_id=?",id);
        if (rows.isEmpty()) throw new NoSuchElementException("设备不存在");
        return rows.get(0);
    }

    public List<Map<String,Object>> snapshots() {
        return rows("SELECT s.*,d.device_code FROM device_snapshot s JOIN device d ON d.id=s.device_id ORDER BY s.device_id");
    }

    public Map<String,Object> deviceView(long id) { return rows("SELECT * FROM device WHERE id=?",id).get(0); }

    // Register without inventing measurements：由外层事务把台账与未知快照一起创建，未知业务列保持NULL。
    public long create(String code,String name,String host,int port,int unit,boolean enabled) {
        jdbc.update("INSERT INTO device (device_code,name,host,port,unit_id,enabled,config_revision,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",code,name,host,port,unit,enabled);
        long id = jdbc.queryForObject("SELECT id FROM device WHERE device_code=?",Long.class,code);
        jdbc.update("INSERT INTO device_snapshot (device_id,connection_status,data_quality,run_state,snapshot_revision,updated_at) "
                + "VALUES (?,?,'UNKNOWN','UNKNOWN',0,UTC_TIMESTAMP(3))",id,enabled ? "UNKNOWN" : "DISABLED");
        return id;
    }

    public void patch(long id,String name,boolean enabled) {
        jdbc.update("UPDATE device SET name=?,enabled=?,config_revision=config_revision+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                name,enabled,id);
    }

    public void persist(Device device, DeviceState state, boolean fresh, boolean saveHistory,
                        String sampleKey, Instant at, String reason) {
        transaction(() -> { write(device,state,fresh,saveHistory,sampleKey,at,reason); return null; });
    }

    /**
     * Write inside the caller transaction：采集经persist进入，启停经MonitorService.patch的外层事务进入。
     * 先锁device_snapshot的该设备行，再读旧状态和活动槽；同设备写入以一致顺序串行化。
     * 这里的fresh是本次候选的新观测资格；saveHistory是10秒采样间隔资格，两者均满足才新增历史。
     */
    void write(Device device, DeviceState state, boolean fresh, boolean saveHistory,
                       String key, Instant at, String reason) {
        long id = device.id();
        Map<String,Object> old = rows("SELECT * FROM device_snapshot WHERE device_id=? FOR UPDATE",id).get(0);
        // Same committed sample: 持有设备行锁后检查样本身份，重复交付不重写快照或事件。
        if (fresh && saveHistory && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM device_sample_history WHERE sample_key=? AND device_id=?)",
                Boolean.class,key,id))) return;
        DeviceSample sample = state.sample;
        Object[] values = sample == null ? new Object[7] : new Object[]{sample.batteryPercent(),sample.speedMmS(),
                sample.positionCode(),sample.targetCode(),sample.faultCode(),sample.heartbeat(),sample.runState().name()};
        String run = sample == null ? "UNKNOWN" : sample.runState().name();
        jdbc.update("UPDATE device_snapshot SET connection_status=?,data_quality=?,run_state=?,battery_percent=?,speed_mm_s=?,"
                + "position_code=?,target_code=?,fault_code=?,heartbeat=?,last_response_at=?,last_fresh_at=?,"
                + "snapshot_revision=snapshot_revision+1,updated_at=? WHERE device_id=?",state.connection,state.quality,run,
                values[0],values[1],values[2],values[3],values[4],values[5],time(state.lastResponseAt),time(state.lastFreshAt),time(at),id);
        // Record discrete changes：电量/速度每次变化不产生状态事件；连接、质量、运行状态和故障码才比较。
        // Observation time only：occurred_at是后端判定时间，不能据此还原两次轮询间未观察到的短暂停车。
        event(id,"CONNECTION",old.get("connectionStatus"),state.connection,at,reason);
        event(id,"DATA_QUALITY",old.get("dataQuality"),state.quality,at,reason);
        event(id,"RUN_STATE",old.get("runState"),run,at,reason);
        if (sample != null) event(id,"FAULT_CODE",old.get("faultCode"),sample.faultCode(),at,reason);
        if (fresh && saveHistory) {
            // Reuse sample identity: 事务重试复用 UUID；真实失败期间缺失的样本不补零、不伪造。
            jdbc.update("INSERT INTO device_sample_history (sample_key,device_id,sampled_at,run_state,battery_percent,"
                    + "speed_mm_s,position_code,target_code,fault_code,heartbeat,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    key,id,time(state.lastFreshAt),run,sample.batteryPercent(),sample.speedMmS(),sample.positionCode(),
                    sample.targetCode(),sample.faultCode(),sample.heartbeat(),time(at));
        }
        for (String rule:List.of("DEVICE_OFFLINE","LOW_BATTERY","DEVICE_FAULT","DATA_STALE"))
            alarm(id,rule,state,fresh,at);
    }

    private void event(long id,String type,Object old,Object next,Instant at,String reason) {
        String before = old == null ? null : old.toString();
        String after = next == null ? null : next.toString();
        if (!Objects.equals(before,after)) jdbc.update("INSERT INTO device_state_event "
                + "(device_id,event_type,old_value,new_value,occurred_at,reason) VALUES (?,?,?,?,?,?)",id,type,before,after,time(at),reason);
    }

    /**
     * Use persisted active slots：按device_id + rule_code查询本轮活动事件，不能每台设备只允许一种异常。
     * 唯一索引uk_alarm_active约束(device_id, rule_code, active_slot)；ACTIVE用1，关闭用NULL。
     * MySQL允许多个NULL槽的历史记录，因此“19→25→19”应保留两轮告警，不复用已恢复记录。
     * GREATEST防止墙上时间回拨使结束/观察时间倒退；它不改变单调时钟计算的离线阈值。
     */
    private void alarm(long id,String rule,DeviceState state,boolean fresh,Instant at) {
        var active=jdbc.queryForList("SELECT id FROM alarm WHERE device_id=? AND rule_code=? AND active_slot=1",Long.class,id,rule);
        AlarmRules.Action action=AlarmRules.decide(rule,state,fresh,!active.isEmpty(),settings);
        if (action==AlarmRules.Action.NONE) return;
        String value=rule.equals("DEVICE_OFFLINE") ? state.connection : rule.equals("DATA_STALE") ? state.quality : state.sample==null ? null :
                String.valueOf(rule.equals("LOW_BATTERY") ? state.sample.batteryPercent() : state.sample.faultCode());
        switch (action) {
            case OPEN -> jdbc.update("INSERT INTO alarm (device_id,rule_code,severity,status,active_slot,trigger_value,`last_value`,"
                    + "triggered_at,last_observed_at) VALUES (?, ?, ?, 'ACTIVE',1,?,?,?,?)",id,rule,
                    (rule.equals("LOW_BATTERY") || rule.equals("DATA_STALE")) ? "WARNING" : "CRITICAL",value,value,time(at),time(at));
            case RECOVER -> jdbc.update("UPDATE alarm SET status='RECOVERED',active_slot=NULL,recovered_at=GREATEST(?,last_observed_at),"
                    + "closed_at=GREATEST(?,last_observed_at),close_reason='RULE_RECOVERED' WHERE id=?",time(at),time(at),active.get(0));
            case OBSERVE -> jdbc.update("UPDATE alarm SET `last_value`=?,last_observed_at=GREATEST(?,last_observed_at) WHERE id=?",
                    value,time(at),active.get(0));
            case SUPPRESS -> jdbc.update("UPDATE alarm SET status='SUPPRESSED',active_slot=NULL,closed_at=GREATEST(?,last_observed_at),"
                    + "close_reason='DEVICE_DISABLED' WHERE id=?",time(at),active.get(0));
            case NONE -> throw new IllegalStateException("NONE was handled before SQL");
        }
    }

    /**
     * Acknowledge once：COALESCE保留首次知悉时间，重复点击不会改触发时间、活动槽或异常状态。
     * affected rows为0既可能已确认，也可能资源不存在，因此再检查存在性，不把幂等操作误报404。
     */
    public Map<String,Object> ack(long id) {
        int changed = jdbc.update("UPDATE alarm SET acknowledged_at=COALESCE(acknowledged_at,GREATEST(UTC_TIMESTAMP(3),triggered_at)) WHERE id=?",id);
        if (changed==0 && jdbc.queryForObject("SELECT COUNT(*) FROM alarm WHERE id=?",Long.class,id)==0)
            throw new NoSuchElementException("告警不存在");
        return rows("SELECT a.*,d.device_code FROM alarm a JOIN device d ON d.id=a.device_id WHERE a.id=?",id).get(0);
    }

    public Map<String,Object> devicesPage(Boolean enabled,int page,int size) {
        String where = enabled==null ? "" : " WHERE enabled=?";
        return page("device","SELECT * FROM device",where,"id ASC",enabled==null ? List.of() : List.of(enabled),page,size);
    }

    /**
     * Query observations, not reconstructed history：使用[from,to)左闭右开窗口，相邻窗口边界不重复。
     * 同一时间可能多条记录，因此追加id倒序作为稳定排序；OFFSET分页不承诺跨并发写入的快照一致性。
     * table/column来自代码内布尔分支，外部的设备ID、时间与分页值均走占位参数。
     */
    public Map<String,Object> timeline(long id,boolean events,Instant from,Instant to,int page,int size) {
        device(id);
        String table = events ? "device_state_event" : "device_sample_history";
        String column = events ? "occurred_at" : "sampled_at";
        return page(table,"SELECT * FROM "+table," WHERE device_id=? AND "+column+">=? AND "+column+"<?",
                column+" DESC,id DESC",List.of(id,time(from),time(to)),page,size);
    }

    public Map<String,Object> alarms(Long deviceId,String rule,String status,int page,int size) {
        var parameters = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (deviceId!=null) { where+=" AND a.device_id=?"; parameters.add(deviceId); }
        if (rule!=null) { where+=" AND a.rule_code=?"; parameters.add(rule); }
        if (status!=null) { where+=" AND a.status=?"; parameters.add(status); }
        return page("alarm a JOIN device d ON d.id=a.device_id","SELECT a.*,d.device_code FROM alarm a JOIN device d ON d.id=a.device_id",
                where,"a.triggered_at DESC,a.id DESC",parameters,page,size);
    }

    // Trusted SQL structure only：表名、排序片段仅来自本类固定调用，绝不接收客户端原样SQL片段。
    // Use long offsets：先提升为long再乘，避免页码较大时int溢出；数据值仍由JdbcTemplate绑定。
    private Map<String,Object> page(String table,String select,String where,String order,List<?> parameters,int page,int size) {
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,parameters.toArray());
        var args = new ArrayList<Object>(parameters); args.add(size); args.add((long)(page-1)*size);
        return Map.of("items",rows(select+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",args.toArray()),
                "page",page,"size",size,"total",total);
    }

    /**
     * Keep retention separate：T15，只清理严格早于UTC截止时间的采样，等于截止时间的记录保留。
     * 每次调用独立短事务，限制1000行以减小锁持有范围；批间提交意味着中途失败后可从剩余旧行继续。
     */
    public int deleteHistoryBefore(Instant cutoff,int limit) {
        if (limit<1 || limit>1000) throw new IllegalArgumentException("History batch size must be 1..1000");
        // Bound each transaction: 只删采样历史，按已建时间索引排序，不触碰告警/状态事件。
        return transaction(() -> jdbc.update("DELETE FROM device_sample_history WHERE sampled_at < ? ORDER BY sampled_at,id LIMIT ?",
                time(cutoff),limit));
    }

    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM device",Long.class); }

    /**
     * Map storage units to API values：DATETIME本身不携带时区，本项目连接和持久化统一按UTC解释。
     * id及revision转字符串避免浏览器大整数精度丢失，unit_id仍是小整数；速度由mm/s转m/s。
     * Map只是内部中间结果，最终由ApiViews的显式record选择字段，不能把数据库任意新列直接输出。
     */
    private List<Map<String,Object>> rows(String sql,Object... args) {
        return jdbc.query(sql,(rs,n) -> {
            var row = new LinkedHashMap<String,Object>();
            var metadata = rs.getMetaData();
            for (int i=1;i<=metadata.getColumnCount();i++) {
                String column = metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);
                Object value = rs.getObject(i);
                if (value instanceof Timestamp timestamp) value=timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
                if (value instanceof LocalDateTime local) value=local.toInstant(ZoneOffset.UTC);
                if (value!=null && Set.of("id","device_id","config_revision","snapshot_revision").contains(column))
                    value=value.toString();
                if (column.equals("speed_mm_s")) { row.put("speedMps",value==null ? null : ((Number)value).doubleValue()/1000.0); continue; }
                if (column.equals("active_slot") || column.equals("sample_key")) continue;
                String[] parts=column.split("_"); StringBuilder key=new StringBuilder(parts[0]);
                for (int j=1;j<parts.length;j++) key.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
                row.put(key.toString(),value);
            }
            return row;
        },args);
    }

    private static LocalDateTime time(Instant at) { return at==null ? null : LocalDateTime.ofInstant(at,ZoneOffset.UTC); }
}
