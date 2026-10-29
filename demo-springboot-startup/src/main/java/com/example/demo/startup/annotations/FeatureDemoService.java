package com.example.demo.startup.annotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import com.example.demo.startup.annotations.feature.FeatureProbeApp;
import com.example.demo.startup.annotations.feature.StartupFeatureApi;
import com.example.demo.startup.annotations.feature.StartupFeatureProperties;
import com.example.demo.startup.annotations.feature.StartupFeatureSelector;

/**
 * 场景 0 的「跑起来」部分：{@code @EnableXxx → ImportSelector → 配置类} 这条链的实测报告。
 *
 * <p>定位：光有注解树和文件清单还不够，得让读者看见「选择器真的被调用了、
 * 而且拿到了标注它的那个类的元数据」。本服务给出两组样本：
 * 主应用常驻的 basic 档（{@code FeatureEnableDemoConfig} 触发），
 * 以及临时起的 full 档 NONE 子应用（{@link FeatureProbeApp} 触发）。
 * 两次调用都记在 {@link StartupFeatureSelector#history()} 里，
 * 于是「谁启用、mode 是什么、返回了哪些类名」是逐条可核对的。</p>
 *
 * <p>关键实现：子应用用 {@code --demo.startup.annotations.retries=7} 覆盖 yaml 的 2，
 * 顺带把「命令行 > application.yaml」这条优先级在同一次响应里演示一遍
 * （场景 3 讲属性源优先级时用的是别的键，这里补一个绑定后的例子）。
 * 子应用跑完立即 {@code close()}，且 {@code setRegisterShutdownHook(false)}，
 * 否则每次点击接口都会留一套线程与 Bean 定义在进程里。</p>
 *
 * <p>坑：① 子应用和主应用共享同一份 {@code application.yaml}（同一个 classloader），
 * 不是「子应用没配置」；② {@code StartupFeatureSelector.history()} 是<b>静态</b>列表，
 * 每调一次接口就多一条 full 记录，读者数历史时会看到重复——这是刻意的，
 * 用来证明「选择器每次解析都会跑一遍」；③ 子应用是 NONE 模式，
 * 所以 {@code Bean} 定义数明显小于主应用，不能拿它和场景 1 的 174 直接比。</p>
 */
@Service
public class FeatureDemoService {

    /** 选择器的一次调用记录（从静态历史里原样取出） */
    public record SelectorCall(String importingClass, String mode, List<String> imports) {
    }

    /**
     * 一档的容器现状。
     *
     * @param apiBeanNames {@code StartupFeatureApi} 类型的 Bean 名（名字来自 @Bean 方法名）
     * @param propertiesBeanNames {@code StartupFeatureProperties} 的 Bean 名；
     *            basic 档应当为空——这直接证明 {@code @EnableConfigurationProperties} 才是注册入口
     */
    public record TierState(String tier, int beanDefinitionCount, List<String> apiBeanNames, String apiSummary,
            List<String> propertiesBeanNames, List<String> boundValues, String contextClass) {
    }

    /** 总报告；{@code fullStartMillis} 单独给，因为它是本接口现起的子应用 */
    public record FeatureReport(List<SelectorCall> selectorHistory, TierState residentBasic,
            TierState childFull, long fullStartMillis, String autoConfigSelectorClass,
            boolean autoConfigSelectorIsDeferred, String conclusion) {
    }

    private final ConfigurableApplicationContext context;

    /** 只注入主容器：basic 档的现状直接从它身上读，full 档另起临时容器并在跑完后立即关闭 */
    public FeatureDemoService(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /**
     * 生成报告。
     *
     * <p>注意子应用启动耗时 0.5~2 秒属正常（要重新解析一遍自动配置）。</p>
     */
    public FeatureReport report() {
        TierState basic = state("basic", context);
        long begin = System.currentTimeMillis();
        TierState full = runFullTier();
        long cost = System.currentTimeMillis() - begin;
        // 历史必须<b>在子应用跑完之后</b>再取：先取会只看到主应用那一条 basic 记录，
        // 于是「full 档也被解析过」这条最关键的证据凭空消失（第一版就栽在顺序上）
        List<SelectorCall> history = StartupFeatureSelector.history().stream()
                .map(resolution -> new SelectorCall(resolution.importingClass(), resolution.mode(),
                        resolution.imports()))
                .toList();
        return new FeatureReport(history, basic, full, cost,
                StartupFeatureSelector.autoConfigSelectorClassName(),
                StartupFeatureSelector.autoConfigSelectorIsDeferred(),
                "同一个注解、两种 mode，得到两个不同的配置类："
                        + "basic 档 propertiesBeanNames 为空，full 档才有属性 Bean 与绑定值——"
                        + "@EnableXxx 的全部魔法就是 @Import + 一个选择器。"
                        + "对照：自动配置用的 " + StartupFeatureSelector.autoConfigSelectorClassName()
                        + " 实现 DeferredImportSelector（" + StartupFeatureSelector.autoConfigSelectorIsDeferred()
                        + "），所以它排在所有用户配置之后展开");
    }

    /** 起一个 NONE 子应用跑 full 档，跑完立即关闭 */
    private TierState runFullTier() {
        SpringApplication application = new SpringApplication(FeatureProbeApp.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        // 不注册 shutdown hook：子容器由本方法显式 close，交给 JVM 钩子会留下重复关闭
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext child = null;
        try {
            // 命令行参数优先级最高，用来覆盖 application.yaml 里的 retries=2
            child = application.run("--demo.startup.annotations.retries=7");
            return state("full", child);
        } finally {
            if (child != null) {
                child.close();
            }
        }
    }

    private TierState state(String tier, ConfigurableApplicationContext target) {
        List<String> apiNames = Arrays.asList(target.getBeanNamesForType(StartupFeatureApi.class));
        List<String> propertyNames = Arrays.asList(target.getBeanNamesForType(StartupFeatureProperties.class));
        // 按名字取，不用 getBean(类型)：full 子应用与主应用若同时装了两档，
        // 按类型取会抛 NoUniqueBeanDefinitionException——而“装了两档”本身就是要暴露的错误，
        // 用名字取才能把现状如实报出来（apiNames.size() 就是证据字段）
        StartupFeatureApi api = apiNames.isEmpty() ? null : target.getBean(apiNames.get(0), StartupFeatureApi.class);
        List<String> bound = new ArrayList<>();
        if (!propertyNames.isEmpty()) {
            StartupFeatureProperties properties = target.getBean(propertyNames.get(0), StartupFeatureProperties.class);
            // 三元组回显：绑定值 + 未绑定时的默认值，读者能一眼看出「绑定到底发生没发生」
            bound.add("names=" + properties.getNames());
            bound.add("retries=" + properties.getRetries());
            bound.add("featureLabel=" + properties.getFeatureLabel());
        }
        return new TierState(tier, target.getBeanDefinitionCount(), apiNames,
                api == null ? "（无 StartupFeatureApi Bean）" : api.tier() + "/boundRetries=" + api.boundRetries()
                        + "/boundNames=" + api.boundNames(),
                propertyNames, bound, target.getClass().getSimpleName());
    }
}
