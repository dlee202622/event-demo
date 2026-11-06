package com.example.demo.txn.loyalty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 分布式事务演示 · C 积分服务（端口 9010）。
 *
 * <p>角色：分布式事务的「参与者」，与 B（库存服务）结构对称——
 * 同一套 2PC / TCC / Saga / Seata 分支接口，业务换成「扣积分」。
 * 教程里 C 是最常被注入故障的一方：它排在调用链最末端，
 * A→B 成功后 C 失败，正好触发「已执行者的补偿」这条关键路径。</p>
 *
 * <p>与 B 的结构差异（有意为之，教学对照）：</p>
 * <ul>
 *   <li>tcc_log 里存了 customer_id——Cancel 时不需要像 B 那样固定还原对象，
 *       展示 TCC 日志的「正确写法」；</li>
 *   <li>多一张 points_ledger 流水表——Saga 扣减/补偿逐笔记账，
 *       补偿实验后能看到「一次 DEDUCT + 一次 COMPENSATE」的完整痕迹。</li>
 * </ul>
 */
@SpringBootApplication
public class LoyaltyApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoyaltyApplication.class, args);
    }
}
