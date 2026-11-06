package com.example.demo.txn.order.controller;

import com.example.demo.txn.order.core.SeataOrderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Seata AT 入口。前置条件：docker compose up -d seata-server，三个服务都启用 Seata。
 *
 * <pre>
 * # 全局提交
 * curl -X POST 'http://localhost:9008/api/seata/order'
 *
 * # 全局回滚：把 C 打成 FAIL，B 的扣减会被自动反向补偿
 * curl -X POST 'http://localhost:9010/api/fault?mode=FAIL'
 * curl -X POST 'http://localhost:9008/api/seata/order'
 * curl 'http://localhost:9009/api/stock/P-1001'   # available 不变，undo_log 归零
 * </pre>
 */
@RestController
@RequestMapping("/api/seata")
public class SeataController {

    private final SeataOrderService service;

    public SeataController(SeataOrderService service) {
        this.service = service;
    }

    /** 发起 AT 全局事务。响应里的 xid 可到 TC 控制台（http://localhost:7091）核对。 */
    @PostMapping("/order")
    public Map<String, Object> order(@RequestParam(defaultValue = "1") int qty,
                                     @RequestParam(defaultValue = "10") int points) {
        return service.place(qty, points);
    }
}
