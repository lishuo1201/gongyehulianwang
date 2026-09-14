package com.example.agv.api;

import com.example.agv.monitoring.MonitorService;
import com.example.agv.monitoring.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.ErrorResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Keep failure semantics：T09/T16，将请求错误、资源冲突、依赖不可用和程序异常分层呈现。
 * 数据库不可用返回503，不伪装为车辆OFFLINE或把最后旧值当本次成功结果返回；未识别内部错误用500。
 * 原始异常留在带traceId的日志，客户端只收到安全说明；数据库约束名称用于分类，不把SQL和连接串公开。
 * SSE响应已经关闭时没有再返回JSON的合法通道，此时只记录断开并由流任务回收资源。
 */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    ResponseEntity<?> bad(Exception error) { return error(400,"INVALID_REQUEST",error instanceof IllegalArgumentException ? error.getMessage() : "请求 JSON 不合法"); }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<?> missing(Exception error) { return error(404,"NOT_FOUND",error.getMessage()); }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate(DuplicateKeyException conflict) {
        // Classify known constraints only: 不把包含连接串的数据库异常原文返回给浏览器。
        if (MonitorRepository.constraint(conflict,"uk_device_code"))
            return error(409,"DEVICE_CODE_EXISTS","设备编码已存在");
        if (MonitorRepository.constraint(conflict,"uk_device_endpoint"))
            return error(409,"DEVICE_ENDPOINT_EXISTS","连接目标已存在");
        return internal(conflict);
    }
    @ExceptionHandler(MonitorService.CapacityException.class)
    ResponseEntity<?> capacity(Exception error) { return error(409,"DEVICE_LIMIT_REACHED","已达到设备登记上限"); }
    @ExceptionHandler({DataAccessResourceFailureException.class,TransientDataAccessResourceException.class,
            QueryTimeoutException.class,CannotCreateTransactionException.class,TransactionTimedOutException.class})
    ResponseEntity<?> unavailable(Exception failure) {
        return failure(503,"DATABASE_UNAVAILABLE","数据库暂不可用，请稍后重试",failure);
    }
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void disconnected(AsyncRequestNotUsableException failure) {
        // Response is closed: SSE 客户端断开后不能再写 JSON 错误体；发送任务负责释放资源。
        log.debug("Async client disconnected",failure);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internal(Exception failure) {
        if (failure instanceof ErrorResponse response)
            return error(response.getStatusCode().value(),"HTTP_ERROR","请求无法处理");
        return failure(500,"INTERNAL_ERROR","服务内部错误",failure);
    }
    private static ResponseEntity<?> failure(int status,String code,String message,Exception failure) {
        // Correlate safely: 日志和响应使用同一 traceId，客户端只收到安全说明。
        String trace=UUID.randomUUID().toString();
        log.error("API failure traceId={}",trace,failure);
        return body(status,code,message,trace);
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException failure) { return error(failure.getStatusCode().value(),"SERVICE_UNAVAILABLE",failure.getReason()); }
    private static ResponseEntity<?> error(int status,String code,String message) {
        return body(status,code,message,UUID.randomUUID().toString());
    }
    private static ResponseEntity<?> body(int status,String code,String message,String trace) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("code",code,"message",message==null ? "请求失败" : message,
                "fieldErrors",List.of(),"traceId",trace));
    }
}
