package com.example.demo.txn.loyalty.controller;

import com.example.demo.txn.loyalty.support.FaultRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 故障注入开关（积分侧）。教程最常用的开关：把 C 打成 FAIL 触发全局回滚/补偿链。
 *
 * <pre>
 * POST /api/fault?mode=FAIL     # C 的所有业务入口直接 409
 * POST /api/fault?mode=NONE     # 恢复
 * </pre>
 */
@RestController
@RequestMapping("/api/fault")
public class FaultController {

    private final FaultRegistry fault;

    public FaultController(FaultRegistry fault) {
        this.fault = fault;
    }

    /** 设置故障模式。mode=NONE|FAIL|HANG；hangMs 仅 HANG 有效，毫秒。 */
    @PostMapping
    public Map<String, Object> set(@RequestParam FaultRegistry.Mode mode,
                                   @RequestParam(required = false) Long hangMs) {
        fault.set(mode, hangMs);
        return status();
    }

    /** 查看当前故障状态。 */
    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", "loyalty");
        out.put("mode", fault.mode());
        out.put("hangMs", fault.hangMs());
        return out;
    }
}
