package com.example.demo.txn.loyalty.controller;

import com.example.demo.txn.loyalty.support.FaultRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常映射（与 B 对齐）：业务失败/故障注入 → 409，参数错误 → 400，
 * 协调者 A 只看状态码即可分辨参与者的三类结局。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail onBusinessFailure(IllegalStateException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("business_rejected");
        return pd;
    }

    @ExceptionHandler(FaultRegistry.FaultInjectedException.class)
    public ProblemDetail onFault(FaultRegistry.FaultInjectedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("fault_injected");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onBadArgument(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("bad_request");
        return pd;
    }
}
