package com.example.demo.txn.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 分布式事务演示 · A 订单服务（端口 9008）。
 *
 * <p>角色：分布式事务的「协调者/发起方」。业务主线：下单 → 扣库存（B）→ 扣积分（C），
 * 同一条业务线用五种方案各实现一遍，互相可以对照：</p>
 *
 * <ul>
 *   <li>POST /api/txn2pc/order——两阶段提交：A 当协调者，Prepare B/C 再统一决议；</li>
 *   <li>POST /api/tcc/order——TCC：Try 冻结 → Confirm/Cancel；</li>
 *   <li>POST /api/saga/order——Saga：正向扣减 + 失败补偿（无隔离性窗口可观测）；</li>
 *   <li>POST /api/localmsg/order——本地消息表：本地事务 + 异步重投（最终一致）；</li>
 *   <li>POST /api/seata/order——Seata AT：@GlobalTransactional 一行注解（见 README Seata 章节）。</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} 为本地消息表的重投器服务；
 * 其余方案的协调逻辑全部同步执行、逐步返回，教程能对着响应看协议走向。</p>
 */
@SpringBootApplication
@EnableScheduling
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
