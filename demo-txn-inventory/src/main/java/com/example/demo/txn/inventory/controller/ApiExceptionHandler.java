package com.example.demo.txn.inventory.controller;

import com.example.demo.txn.inventory.core.SagaParticipant;
import com.example.demo.txn.inventory.core.SeataBranchService;
import com.example.demo.txn.inventory.core.TccParticipant;
import com.example.demo.txn.inventory.core.TwoPcParticipant;
import com.example.demo.txn.inventory.support.FaultRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常映射：把服务内部异常翻译成稳定的 HTTP 语义，
 * 协调者 A 只看状态码就能分辨「业务失败(409)」和「系统错误(500)」。
 *
 * <p>为什么用 ProblemDetail（RFC 7807）：Spring Boot 4 原生支持，
 * 响应体自带 type/title/detail 字段，教程截图里错误信息一目了然。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 库存不足 / 协议状态非法：参与者明确投 NO 或执行失败，不是基础设施故障。 */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail onBusinessFailure(IllegalStateException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("business_rejected");
        return pd;
    }

    /** 故障注入：和业务失败同码（409），detail 里带「故障注入」字样便于教学识别。 */
    @ExceptionHandler(FaultRegistry.FaultInjectedException.class)
    public ProblemDetail onFault(FaultRegistry.FaultInjectedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("fault_injected");
        return pd;
    }

    /** 参数错误（商品不存在、qty 非法等）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onBadArgument(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("bad_request");
        return pd;
    }
}
