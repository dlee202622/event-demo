package com.example.demo.concurrency.support;

/**
 * 线程创建与等待的小工具，避免每个实验重复写 try/catch。
 *
 * <p>所有演示线程都设成守护线程（daemon）：即使实验中途出错、某个线程还卡在锁上，
 * 也不会阻止 Spring Boot 进程退出。真实业务里的工作线程通常不是守护线程。</p>
 */
public final class DemoThreads {

    private DemoThreads() {
    }

    /**
     * 创建并立即启动一个命名线程。
     *
     * <p>线程名会出现在 ThreadMXBean 快照和死锁检测结果里，所以调用方要传入
     * “场景 + 编号 + 角色”的可读名字。</p>
     *
     * @param name 线程名，建议全局唯一（同一场景的多次请求也要区分）
     * @param body 线程体
     * @return 已启动的线程，调用方可 join / interrupt
     */
    public static Thread start(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 等待线程结束，最多等 {@code millis} 毫秒。
     *
     * @return true 表示线程已结束；false 表示超时仍在运行
     */
    public static boolean join(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            // 调用方（通常是 HTTP 请求线程）被中断时恢复标记并返回，让上层决定是否放弃
            Thread.currentThread().interrupt();
            return false;
        }
        return !thread.isAlive();
    }
}
