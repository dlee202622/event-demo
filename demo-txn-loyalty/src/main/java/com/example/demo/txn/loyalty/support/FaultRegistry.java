package com.example.demo.txn.loyalty.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 故障注入开关：演示分布式事务的核心手段——不重启服务就能把 B 打成
 * 「业务必败（FAIL）」或「处理缓慢（HANG）」两种状态。
 *
 * <p>为什么需要它：Saga / TCC / 2PC 的每条异常路径都依赖「参与者在中途失败」，
 * 真实的宕机难以稳定复现，用开关把故障变成确定性事件，教程里的每个实验才可重复。</p>
 *
 * <p>语义（三选一，互斥）：</p>
 * <ul>
 *   <li>NONE——正常执行；</li>
 *   <li>FAIL——业务入口直接抛 {@link FaultInjectedException}，模拟参与者宕机/拒绝；</li>
 *   <li>HANG——先阻塞 hangMs 再正常执行，模拟 GC 停顿/慢查询。
 *       2PC 实验用它拉长「Prepare 后锁滞留」的窗口，肉眼可观测。</li>
 * </ul>
 *
 * <p>限制：开关只作用于本进程，重启即失效（内存态），这是刻意的——
 * 故障注入属于实验行为，不应当被持久化。</p>
 */
@Component
public class FaultRegistry {

    /** 故障模式。用 AtomicReference 保证「读-改-写」在并发请求下可见。 */
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NONE);

    /** HANG 模式的阻塞时长，毫秒。默认 8 秒：够长（肉眼观察锁滞留）又短于 HTTP 读超时。 */
    private final AtomicLong hangMs = new AtomicLong(8_000);

    public enum Mode { NONE, FAIL, HANG }

    /**
     * 每个参与者业务入口第一行调用：命中 FAIL 抛异常，命中 HANG 先阻塞。
     *
     * @param point 故障点名称，进异常信息，方便在 A 的响应里看到是哪个环节被打断
     */
    public void check(String point) {
        Mode current = mode.get();
        // HANG 先睡再放行：睡完继续执行而不是抛错，这样「慢」和「挂」能分别观察
        if (current == Mode.HANG) {
            try {
                Thread.sleep(hangMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("HANG 等待被中断", e);
            }
        } else if (current == Mode.FAIL) {
            throw new FaultInjectedException("故障注入：" + point + " 被强制失败");
        }
    }

    /** 切换故障模式；hangMs 仅 HANG 模式使用，单位毫秒。 */
    public void set(Mode newMode, Long newHangMs) {
        mode.set(newMode);
        if (newHangMs != null) {
            hangMs.set(newHangMs);
        }
    }

    public Mode mode() {
        return mode.get();
    }

    public long hangMs() {
        return hangMs.get();
    }

    /** 故障注入导致的业务失败，HTTP 层映射成 409，与真实库存不足区分开。 */
    public static class FaultInjectedException extends RuntimeException {
        public FaultInjectedException(String message) {
            super(message);
        }
    }
}
