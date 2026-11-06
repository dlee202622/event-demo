package com.example.demo.txn.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 分布式事务演示 · B 库存服务（端口 9009）。
 *
 * <p>角色：分布式事务的「参与者」。A（订单服务）作为协调者/发起方，
 * 通过 HTTP 调用本服务暴露的四套分支接口，同一个「扣库存」动作
 * 分别用 2PC / TCC / Saga / Seata AT 四种协议语义实现一遍：</p>
 *
 * <ul>
 *   <li>/api/txn2pc/*——两阶段提交：Prepare 后本地事务保持打开、行锁滞留；</li>
 *   <li>/api/tcc/*——Try 冻结库存，Confirm/Cancel 二选一收尾；</li>
 *   <li>/api/saga/*——正向操作直接扣，失败后由 A 调补偿接口加回；</li>
 *   <li>/api/seata/*——Seata AT 分支：只写普通 @Transactional，回滚交给框架。</li>
 * </ul>
 *
 * <p>刻意不引入任何事务中间件框架（Seata 除外，作为对比项），
 * 每一行协议代码都手写，这样教程才能对着源码讲每一步发生了什么。</p>
 *
 * <p>坑：Prepare 持有的未决事务会占住 Hikari 连接池的一个连接，
 * 并发未决事务数超过池上限（默认 10）后，新的 Prepare 会在取连接处排队。</p>
 */
@SpringBootApplication
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
