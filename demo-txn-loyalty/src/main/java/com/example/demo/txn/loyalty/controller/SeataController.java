package com.example.demo.txn.loyalty.controller;

import com.example.demo.txn.loyalty.core.SeataBranchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Seata AT 分支接口（积分侧）：零协议代码，XID 由请求头 TX_XID 经过滤器绑定。
 */
@RestController
@RequestMapping("/api/seata")
public class SeataController {

    private final SeataBranchService service;

    public SeataController(SeataBranchService service) {
        this.service = service;
    }

    /** AT 分支扣减积分。 */
    @PostMapping("/deduct/{customerId}")
    public Map<String, Object> deduct(@PathVariable String customerId,
                                      @RequestParam(defaultValue = "10") int points) {
        return service.deduct(customerId, points);
    }

    /** undo_log 观测。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }
}
