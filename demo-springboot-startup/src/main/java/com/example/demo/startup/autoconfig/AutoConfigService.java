package com.example.demo.startup.autoconfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport.ConditionAndOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport.ConditionAndOutcomes;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import com.example.demo.startup.autoconfig.probe.ProbeApp;

/**
 * 场景 3 的读报告器：把启动时缓存的 {@link ConditionEvaluationReport} 变成接口数据。
 *
 * <p>定位：条件评估发生在 Bean 定义注册期，只执行一次，结果缓存在容器里；
 * 本服务只做“读取 + 过滤”，不重新评估——所以改了条件代码必须重启才生效。</p>
 *
 * <p>坑：① 条件判定**总是**被记录（{@code SpringBootCondition} 每次求值都会写报告），
 * {@code debug=true} 只影响它是否被打印到日志，不影响这里能否读到；
 * 若 {@code find()} 返回 null，说明这个容器压根没跑自动配置；
 * ② 全量条目上千，直接返回会刷屏，摘要 + search 过滤才是正确用法；
 * ③ “没被记录” ≠ “没装载”，无条件 Bean 根本不入报告。</p>
 */
@Service
public class AutoConfigService {

    /** 排除实验用的探针路径；ProbeController 在主应用和子应用里都会被扫到 */
    private static final String PROBE_PATH = "/api/startup/probe/ping";

    private final ConfigurableListableBeanFactory beanFactory;

    /** 同时留下容器与 beanFactory：条件报告要问容器，定义级细节要问 beanFactory */
    public AutoConfigService(ConfigurableApplicationContext context) {
        // 保存 BeanFactory 而不是 Context：只读报告不需要生命周期控制权，
        // 也避免 Service 持有 Context 引用造成“循环依赖错觉”（Boot 3+ 禁止自注入 Context）
        this.beanFactory = context.getBeanFactory();
    }

    /** 单条判定记录：source=@Bean 方法或自动配置类名，matched=是否装载 */
    public record ConditionEntry(String source, String conditionClass, boolean matched, String message) {
    }

    /** 报告摘要：总量 + 关键结论 + 演示条件三兄弟的现状 */
    public record ReportSummary(boolean available, int totalSources, long totalConditions,
            long matchedConditions, long unmatchedConditions, int unconditionalClasses,
            List<String> exclusions, List<ConditionEntry> demoEntries, String hint) {
    }

    /**
     * 总览：报告是否开启、各维度总量、本模块三个演示 Bean 的条件判定。
     *
     * @return available=false 时其余数字均为 0，hint 说明开启方法
     */
    public ReportSummary summary() {
        ConditionEvaluationReport report = ConditionEvaluationReport.find(beanFactory);
        if (report == null) {
            return new ReportSummary(false, 0, 0, 0, 0, 0, List.of(), List.of(),
                    "该容器没有条件评估报告（自动配置未参与本次启动）；主应用正常情况下不会走到这里");
        }
        Map<String, ConditionAndOutcomes> bySource = report.getConditionAndOutcomesBySource();
        long total = 0;
        long matched = 0;
        List<ConditionEntry> demoEntries = new ArrayList<>();
        for (Map.Entry<String, ConditionAndOutcomes> e : bySource.entrySet()) {
            // 只收本模块的演示条目；com.example.demo.startup 前缀在子应用报告里同样命中，
            // 但本实例的 report 来自主容器，不会混入子应用数据
            boolean isDemo = e.getKey().startsWith("com.example.demo.startup");
            for (ConditionAndOutcome co : e.getValue()) {
                total++;
                // isMatch=false 的条目可能拖垮整个配置类；统计口径是“条件个数”而非“类个数”
                if (co.getOutcome().isMatch()) {
                    matched++;
                }
                if (isDemo) {
                    demoEntries.add(toEntry(e.getKey(), co));
                }
            }
        }
        return new ReportSummary(true, bySource.size(), total, matched, total - matched,
                report.getUnconditionalClasses().size(), report.getExclusions(), demoEntries,
                "查看某个类为什么没装载：GET /api/startup/autoconfig/search?contains=类名关键词");
    }

    /**
     * 检索：source、条件类名或判定消息包含关键词的明细（大小写不敏感）。
     *
     * @param contains 关键词，如 FeatureColor / OnPropertyCondition / jdbc
     * @param limit    最多返回条数，防止一次性拉爆响应
     */
    public List<ConditionEntry> search(String contains, int limit) {
        ConditionEvaluationReport report = ConditionEvaluationReport.find(beanFactory);
        if (report == null) {
            return List.of();
        }
        String needle = (contains == null) ? "" : contains.toLowerCase(Locale.ROOT);
        List<ConditionEntry> hits = new ArrayList<>();
        for (Map.Entry<String, ConditionAndOutcomes> e : report.getConditionAndOutcomesBySource().entrySet()) {
            for (ConditionAndOutcome co : e.getValue()) {
                String conditionName = co.getCondition().getClass().getName();
                // 检索面 = source 名 + 条件类名 + 判定消息，三个字段任一命中即返回
                String haystack = (e.getKey() + " " + conditionName + " " + co.getOutcome().getMessage())
                        .toLowerCase(Locale.ROOT);
                if (haystack.contains(needle)) {
                    hits.add(toEntry(e.getKey(), co));
                    if (hits.size() >= limit) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }

    private ConditionEntry toEntry(String source, ConditionAndOutcome co) {
        return new ConditionEntry(source, co.getCondition().getClass().getSimpleName(),
                co.getOutcome().isMatch(), co.getOutcome().getMessage());
    }

    /** 自动配置类清单（按类名前缀过滤），classPresent 只回答“jar 在不在类路径上” */
    public record AutoConfigEntry(String className, boolean classPresent, String note) {
    }

    /**
     * “自动配置清单长什么样”：按关键词找几个代表性自动配置类名。
     *
     * <p>真正的完整清单在
     * {@code spring-boot-autoconfigure-4.1.2.jar!/META-INF/spring/
     * org.springframework.boot.autoconfigure.AutoConfiguration.imports}；
     * 本方法演示的是“自动配置 = 一批普通类 + 排序 + 条件过滤”，不是复制官方清单。</p>
     */
    public List<AutoConfigEntry> listAutoConfigs(String contains) {
        // 注意包名：Boot 4 把自动配置从 spring-boot-autoconfigure 拆进各技术模块，
        // 类名也从 org.springframework.boot.autoconfigure.web.servlet.* 变成
        // org.springframework.boot.webmvc.autoconfigure.*。网上按 Boot 2/3 包名写的教程在这里全部失效。
        List<String> candidates = List.of(
                "org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration",
                "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration",
                "org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration",
                "org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration");
        List<AutoConfigEntry> out = new ArrayList<>();
        String needle = (contains == null) ? "" : contains.toLowerCase(Locale.ROOT);
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).contains(needle)) {
                // 实测两种“没装载”要分清：DataSourceAutoConfiguration 在本模块 present=false，
                // 因为它在 Boot 4 属于 jdbc 模块、类路径上根本没有——不是条件落选。
                // 因此 search?contains=DataSource 返回 []（条件报告里压根没有它的条目），
                // 这正好演示“报告里查不到 ≠ 条件判定为 false”
                out.add(new AutoConfigEntry(c, ClassUtils.isPresent(c, getClass().getClassLoader()),
                        "存在性仅代表类路径可见，装载与否看条件报告"));
            }
        }
        return out;
    }

    /** 排除实验：baseline 与 excluded 两次子应用启动的并排结果 */
    public record ExcludeDemoResult(String excludedAutoConfig, String probePath,
            int baselinePort, int baselineBeanCount, int baselineStatus,
            String excludedOutcome, Integer excludedPort, Integer excludedBeanCount, Integer excludedStatus,
            String excludedFailureClass, String excludedFailureMessage,
            String conclusion, long costMillis) {
    }

    /**
     * 反例实验：“排除一个自动配置”到底意味着什么。
     *
     * <p>做法：同一探针应用起两次（port=0 随机端口），第二次用
     * {@code spring.autoconfigure.exclude} 排除 DispatcherServletAutoConfiguration，
     * 两次都真发 HTTP 请求打探针路径。</p>
     *
     * <p>实测结论（Boot 4.1.2，2026-10-29）：预想的是“容器仍在、路由 404”，
     * 实际是<b>第二个上下文直接启动失败</b>——{@code ErrorMvcAutoConfiguration}
     * 的 errorPageCustomizer 依赖 {@code DispatcherServletPath}（由被排除的配置提供），
     * Tomcat 工厂 Bean 因此在创建阶段就炸。这比 404 更有教学价值：
     * 自动配置之间是有依赖图的，排除一个可能连坐一片。</p>
     *
     * @return 并排对比；成功启动的子应用都在方法结束前 close()，不残留端口
     */
    public ExcludeDemoResult excludeDemo() {
        long begin = System.currentTimeMillis();
        // 控制变量：两次唯一差异就是 spring.autoconfigure.exclude 这一行
        ProbeRun baseline = startProbeChild(List.of("--server.port=0"));
        ProbeRun excluded = startProbeChild(List.of("--server.port=0",
                // 包名必须是 Boot 4 的 org.springframework.boot.webmvc.autoconfigure——
                // 写 Boot 2/3 的旧包名既不报错也不生效（实测：旧包名时探针仍 200）
                "--spring.autoconfigure.exclude=org.springframework.boot.webmvc.autoconfigure"
                        + ".DispatcherServletAutoConfiguration"));
        try {
            String conclusion;
            if (!"OK".equals(excluded.outcome)) {
                conclusion = "已验证：排除 DispatcherServletAutoConfiguration 后子应用根本没起来——"
                        + "ErrorMvcAutoConfiguration 需要它提供的 DispatcherServletPath。"
                        + "结论：自动配置不是孤立开关，是一个有依赖图的集合，排除要连坐评估";
            }
            else if (excluded.status != null && excluded.status == 404 && baseline.status == 200) {
                conclusion = "已验证：容器仍监听 " + excluded.port + "，但 " + PROBE_PATH
                        + " 返回 404（少了接收请求的 DispatcherServlet）";
            }
            else {
                conclusion = "意外结果：baseline=" + baseline.status + " excluded=" + excluded.status
                        + " / " + excluded.outcome;
            }
            return new ExcludeDemoResult("DispatcherServletAutoConfiguration", PROBE_PATH,
                    baseline.port, baseline.beanCount, baseline.status,
                    excluded.outcome, excluded.port, excluded.beanCount, excluded.status,
                    excluded.failureClass, excluded.failureMessage, conclusion,
                    System.currentTimeMillis() - begin);
        }
        finally {
            baseline.close();
            excluded.close();
        }
    }

    /** 一次子应用运行的结果；启动失败时 outcome=FAILED 且 port/beanCount/status 为 null */
    private record ProbeRun(ConfigurableApplicationContext context, String outcome, Integer port,
            Integer beanCount, Integer status, String failureClass, String failureMessage) {

        /** 空上下文安全关闭：失败那次压根没有容器 */
        void close() {
            if (context != null) {
                context.close();
            }
        }
    }

    /**
     * 起一个 SERVLET 子应用并当场请求探针路径。
     *
     * <p>启动失败不抛异常：本实验的目的就是观察“被排除后会发生什么”，
     * 把失败结构化返回才有对比价值。</p>
     */
    private ProbeRun startProbeChild(List<String> args) {
        // ProbeApp 与 ProbeController 同包：两次运行的容器里都有探针路由，
        // baseline 与 excluded 的唯一差异只剩 spring.autoconfigure.exclude
        SpringApplication application = new SpringApplication(ProbeApp.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        // 同 StartupModesService：手动 close 负责生命周期，不留 shutdown hook
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = null;
        try {
            // 参数以命令行形式传入：--server.port=0 必须压过子应用读到的 application.yaml（9006），
            // setDefaultProperties 做不到（优先级最低，实测踩过）
            context = application.run(args.toArray(String[]::new));
            // local.server.port 由 web-server 模块的 ServerPortInfoApplicationContextInitializer
            // 在 WebServer 就绪后写入，port=0 时靠它拿到真实端口
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            // discarding() 只取状态码；JDK HttpClient 没有 BodyHandlers.ofStatusCode
            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + PROBE_PATH))
                            .timeout(Duration.ofSeconds(8))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            ProbeRun run = new ProbeRun(context, "OK", port, context.getBeanDefinitionCount(),
                    response.statusCode(), null, null);
            context = null;
            return run;
        }
        catch (Exception ex) {
            if (context != null) {
                context.close();
            }
            // 顶层消息往往只是“Unable to start web server”，真正的原因在 cause 链最深处，
            // 所以这里一路下钻把根因消息拼上（实验的可读性全靠这一行）
            Throwable root = ex;
            for (int i = 0; i < 8 && root.getCause() != null && root.getCause() != root; i++) {
                root = root.getCause();
            }
            return new ProbeRun(null, "FAILED", null, null, null, ex.getClass().getSimpleName(),
                    ex.getMessage() + " ｜根因: " + root.getClass().getSimpleName() + ": " + root.getMessage());
        }
    }
}
