package com.example.demo.txn.inventory.controller;

import com.example.demo.txn.inventory.core.SeataBranchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Seata AT 分支接口：业务方法只有一个普通 @Transactional，协议代码为零。
 * 全局 XID 通过请求头 TX_XID 传入并由过滤器绑定（过滤器随 Seata 接入一并启用，
 * 见 README 的 Seata 章节），本控制器自身不感知事务。
 */
@RestController
@RequestMapping("/api/seata")
public class SeataController {

    private final SeataBranchService service;

    public SeataController(SeataBranchService service) {
        this.service = service;
    }

    /** AT 分支扣减：全局提交时真实生效，全局回滚时被 undo_log 反向补偿。 */
    @PostMapping("/deduct/{productId}")
    public Map<String, Object> deduct(@PathVariable String productId,
                                      @RequestParam(defaultValue = "1") int qty) {
        return service.deduct(productId, qty);
    }

    /** undo_log 观测：全局事务进行中 count>0，结束后被异步清理。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }
}
