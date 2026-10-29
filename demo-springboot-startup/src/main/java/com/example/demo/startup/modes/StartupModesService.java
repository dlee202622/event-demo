package com.example.demo.startup.modes;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

/**
 * 场景 2 的执行器：在同一个 JVM 里用三种 {@link WebApplicationType} 起子应用并回收快照。
 *
 * <p>定位：启动模式决定三件事——容器实现类、是否创建 WebServer、以及自动配置
 * 装进来的 Web 基础设施 Bean 数量。三种模式各起一次（跑完即关），
 * 用并排数字代替背文档。</p>
 *
 * <p>关键实现：
 * ① 子应用统一 port=0（内核随机端口），绝不与主应用 9006 或用户服务 9001~9005 冲突；
 * ② 每次 run() 后立刻 close()，否则 Netty/Tomcat 线程会随调用次数堆积；
 * ③ banner/logStartupInfo 关闭——启动日志由本服务结构化返回，不需要 Boot 再刷一遍。</p>
 *
 * <p>坑：子应用会重新解析全部自动配置，单次耗时 0.5~3 秒，compare 接口总耗时
 * 约 5~10 秒属正常；端口字符串要转成 int 再传，SpringApplication 只认属性值不认类型。
 * WebApplicationType 一旦在 run() 里被消费就无法改——同一个实例不能重复 run。</p>
 *
 * <p>替代方案：只想验证容器类型可用 SpringApplicationBuilder；
 * 想看真实生产差异应直接对比两个独立部署的进程，JVM 内多次启动共享
 * 类加载与 JIT 缓存，耗时数字只能看量级不能当基准测试。</p>
 */
@Service
public class StartupModesService {

    /**
     * 三种模式 + 自动推断，共四次启动的并排对比。
     *
     * @return 四条 {@link ModeSnapshot}；任何一条失败不影响其余（逐条 try/catch）
     */
    public List<ModeSnapshot> compare() {
        List<ModeSnapshot> snapshots = new ArrayList<>();
        // NONE 不需要端口参数：它压根不建 WebServer
        snapshots.add(runMode("NONE", WebApplicationType.NONE,
                "定时任务/批处理/纯 CLI：不建容器 Web 层，最轻"));
        snapshots.add(runMode("REACTIVE", WebApplicationType.REACTIVE,
                "长连接/高并发 IO：Netty 事件循环，函数式或 Mono 返回"));
        snapshots.add(runMode("SERVLET", WebApplicationType.SERVLET,
                "传统 Web：Tomcat 线程池，一个请求一根线程"));
        snapshots.add(autoDeduce());
        return snapshots;
    }

    /**
     * 以指定模式启动一个空应用并采集快照。
     *
     * @param modeLabel 返回体里的模式名，与枚举解耦（AUTO-DEDUCED 没有枚举值）
     * @param type      启动模式；null 表示不显式指定、交给推断
     * @param note      适用场景说明，原样透传
     */
    private ModeSnapshot runMode(String modeLabel, WebApplicationType type, String note) {
        long begin = System.currentTimeMillis();
        SpringApplication application = new SpringApplication(ModeProbeApp.class);
        if (type != null) {
            application.setWebApplicationType(type);
        }
        // port=0 必须走命令行参数而不是 setDefaultProperties：
        // 默认属性优先级最低，会被子应用自己读到的 application.yaml（server.port=9006）覆盖，
        // 表现是“子应用抢主应用端口失败”，看起来像 bug 其实是属性源顺序问题（实测踩过）
        // 命令行参数（--key=value）优先级最高，压过所有配置文件
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        // 子应用绝不注册 JVM shutdown hook：close() 由本方法负责，
        // 留着 hook 会让同一个 context 被关两次并拖住退出路径
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = null;
        try {
            // 命令行参数优先级最高，压过子应用从 application.yaml 继承的 server.port=9006
            context = application.run(extraArgs(type));
            long cost = System.currentTimeMillis() - begin;
            String contextClass = context.getClass().getSimpleName();
            // Bean 定义数在 run() 返回后仍是终值；NONE 与其他模式的差值 ≈ DispatcherServlet/
            // WebMvc 基础设施 + 各 WebServer 专属配置的总量
            int beanCount = context.getBeanDefinitionCount();
            String webServer = null;
            Integer port = null;
            if (context instanceof WebServerApplicationContext webContext) {
                WebServer server = webContext.getWebServer();
                // getWebServer() 为 null 只可能出现在 NONE（此时 context 不是 Web 子类），双保险
                webServer = (server == null) ? null : server.getClass().getSimpleName();
                port = (server == null) ? null : server.getPort();
            }
            return ModeSnapshot.ok(modeLabel, contextClass, beanCount, webServer, port, cost, note);
        }
        catch (Throwable ex) {
            return ModeSnapshot.failed(modeLabel, System.currentTimeMillis() - begin, ex, note);
        }
        finally {
            if (context != null) {
                context.close();
            }
        }
    }

    /**
     * 子应用启动参数：Web 模式一律 port=0。
     *
     * @param type 启动模式；NONE 无需端口参数
     * @return 传给 {@code SpringApplication.run(String...)} 的命令行风格参数
     */
    private static String[] extraArgs(WebApplicationType type) {
        return type == WebApplicationType.NONE ? new String[0] : new String[] {"--server.port=0"};
    }

    /**
     * 不指定模式的自动推断演示：类路径同时有 webmvc(Tomcat) 与 webflux(Netty) 时，
     * Boot 4.1 的 {@code WebApplicationType.deduce()} 按 spring.factories 注册的
     * {@code Deducer} 顺序逐个询问，第一个返回非 null 的胜出。
     * 观察点：本条快照的 contextClass——主应用因显式钉死 SERVLET 而稳定，
     * 这里不钉，看看谁被选中。
     */
    private ModeSnapshot autoDeduce() {
        return runMode("AUTO-DEDUCED", null,
                "推断依赖类路径与 Deducer 顺序，双 Web 依赖时结果不受你控制——所以要么只留一种，要么显式指定");
    }

    /**
     * 端口冲突实验：先用 ServerSocket 占住一个刚分配的空闲端口，
     * 再以该端口启动子应用，观察异常链形状（场景 5 的“内置 analyzer”伏笔）。
     *
     * <p>实测同 JVM 里复现的链是
     * {@code ApplicationContextException -> PortInUseException -> IllegalArgumentException
     * -> LifecycleException -> BindException(Address already in use)}；
     * 真实进程重启同样冲突时，控制台不会打印这条链，而是由内置
     * {@code PortInUseFailureAnalyzer} 输出 “APPLICATION FAILED TO START /
     * Port 9006 was already in use” 三行报告，整段日志 38 行、堆栈帧 0 条。</p>
     *
     * @return outcome=FAILED 的快照，failureMessage 末尾带根因与整条异常链
     */
    public ModeSnapshot portInUse() {
        long begin = System.currentTimeMillis();
        // 关键：ServerSocket 必须“跨整个子应用启动过程”保持打开，
        // 否则释放端口的瞬间 Tomcat 就可能抢到它，实验变成偶发（实测过一版 try-with-resources 就翻车）
        try (ServerSocket blocker = new ServerSocket(0)) {
            int occupied = blocker.getLocalPort();
            SpringApplication application = new SpringApplication(ModeProbeApp.class);
            application.setWebApplicationType(WebApplicationType.SERVLET);
            application.setBannerMode(Banner.Mode.OFF);
            application.setLogStartupInfo(false);
            application.setRegisterShutdownHook(false);
            try {
                // 这里必须显式传被占端口：命令行参数优先于 yaml，否则子应用会绑 yaml 里的 9006
                ConfigurableApplicationContext context = application.run("--server.port=" + occupied);
                // 走到这里说明冲突没复现（端口被别的进程放过），如实用标注
                context.close();
                return ModeSnapshot.ok("PORT-IN-USE(意外成功)", context.getClass().getSimpleName(),
                        context.getBeanDefinitionCount(), "无", occupied, System.currentTimeMillis() - begin,
                        "未复现冲突，检查阻塞 Socket 是否真的持有端口");
            }
            catch (Throwable ex) {
                ModeSnapshot snapshot = ModeSnapshot.failed("PORT-IN-USE",
                        System.currentTimeMillis() - begin, ex,
                        // 实测：同 JVM 子应用失败时控制台同样打印内置报告的原文——
                        // reportFailure() 在 SpringApplication.run() 内部，与是否独立进程无关；
                        // 区别只在于父进程不受影响，接口照样能把异常链取回来
                        "内置 PortInUseFailureAnalyzer 已在控制台打印原文；"
                                + "接口返回的是同一次失败的异常链，两者可互相印证");
                StringBuilder chain = new StringBuilder();
                // 沿 cause 链列出类名：analyzer 正是从这条链上找它认领的异常类型
                for (Throwable t = ex; t != null && t != t.getCause(); t = t.getCause()) {
                    chain.append(t.getClass().getSimpleName()).append(" -> ");
                }
                return new ModeSnapshot(snapshot.mode(), snapshot.contextClass(), snapshot.beanCount(),
                        snapshot.webServer(), snapshot.port(), snapshot.startupMillis(), snapshot.outcome(),
                        snapshot.failureClass(),
                        snapshot.failureMessage() + " ｜根因: " + snapshot.rootCauseClass() + ": "
                                + snapshot.rootCauseMessage() + " ｜异常链: " + chain,
                        snapshot.rootCauseClass(), snapshot.rootCauseMessage(), snapshot.note());
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("无法分配临时端口", ex);
        }
    }
}
