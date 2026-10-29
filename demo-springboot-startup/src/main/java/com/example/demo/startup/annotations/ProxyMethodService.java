package com.example.demo.startup.annotations;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import com.example.demo.startup.annotations.proxymethod.HandleMaster;
import com.example.demo.startup.annotations.proxymethod.HandleSlave;
import com.example.demo.startup.annotations.proxymethod.ProxyMethodFullConfig;
import com.example.demo.startup.annotations.proxymethod.ProxyMethodLiteConfig;

/**
 * {@code proxyBeanMethods} 对照实验：同一个配置类写法，两种模式差在哪。
 *
 * <p>定位：文档会说「lite 模式更快，因为没有 CGLIB 增强」，但这句话必须能被看见。
 * 本服务读的是主容器里已经实例化好的两个 Bean（{@link ProxyMethodFullConfig} 与
 * {@link ProxyMethodLiteConfig} 代码逐行相同，只有注解参数不同），
 * 给出三组证据：配置类的<b>运行时类名</b>（有没有 CGLIB 后缀）、
 * master 持有的 slave 与容器里的 slave <b>是否同一个对象</b>、
 * 以及该档 {@code HandleSlave} <b>被 new 了几次</b>。</p>
 *
 * <p>关键实现：全程不重建容器——这两个配置类是主应用启动时就注册的常驻 Bean，
 * 所以数字在进程存活期间稳定。用 {@code System.identityHashCode} 只为在 JSON 里
 * 肉眼区分两个实例，真正的相等判断走 {@code ==}（{@code sameInstance} 字段）。</p>
 *
 * <p>坑：① lite 档的 {@code instancesCreated} 是 2 而不是 1，多出的一次来自
 * {@code masterLite()} 里那次「普通方法调用」——这在不同 JDK/Spring 版本上不会变，
 * 但<b>如果你调换 {@code @Bean} 方法声明顺序，序号会换人</b>，别把序号当业务 ID；
 * ② CGLIB 类名后缀（形如 {@code $$SpringCGLIB$$0}）是实现细节，
 * 因此 {@code cglibEnhanced} 用「运行时类名是否等于原类名」判断，不匹配字符串特征；
 * ③ 本模块常驻的两个配置类会让 Bean 总数比教学前多 4 个，
 * 场景 1/3 的计数里已经包含它们，不要以为数字漂了。</p>
 */
@Service
public class ProxyMethodService {

    /**
     * 一档的实测结果。
     *
     * @param configClassRuntime 配置类在容器里的运行时类名（full 档会带 CGLIB 后缀）
     * @param cglibEnhanced 运行时类名 ≠ 声明类名 → 被增强过
     * @param sameInstance master 构造时拿到的 slave 与容器里的 slave Bean 是否 {@code ==}
     * @param instancesCreated 该档 HandleSlave 累计实例数（tier 内计数）
     */
    public record TierReport(String tier, String configClassRuntime, boolean cglibEnhanced,
            int masterHoldsIdentity, int containerIdentity, boolean sameInstance,
            int masterSlaveSerial, int containerSlaveSerial, int instancesCreated, String note) {
    }

    /** 两档并排 + 结论；结论里的每个数字都来自本次实测 */
    public record Experiment(TierReport full, TierReport lite, int slaveTotalAcrossTiers, String conclusion) {
    }

    private final ConfigurableApplicationContext context;

    /** 注入主容器：两档配置类都是常驻 Bean，实验只从主容器读现场，全程不重建容器 */
    public ProxyMethodService(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** 读取常驻 Bean 的现场，生成对照报告 */
    public Experiment run() {
        TierReport full = tier("full", ProxyMethodFullConfig.class, "masterFull", "slaveFull",
                "方法互调被 CGLIB 拦截 → 复用容器单例，方法体只执行一次");
        TierReport lite = tier("lite", ProxyMethodLiteConfig.class, "masterLite", "slaveLite",
                "方法互调就是普通 Java 调用 → 又多 new 了一个实例，且它不进容器");
        return new Experiment(full, lite, HandleSlave.createdCount("full") + HandleSlave.createdCount("lite"),
                "两档唯一区别是 @Configuration 的 proxyBeanMethods 参数："
                        + "full 的 sameInstance=" + full.sameInstance() + "、lite 的 sameInstance="
                        + lite.sameInstance() + "；实例数 " + full.instancesCreated() + " vs "
                        + lite.instancesCreated() + "。lite 不是「不注册 Bean」，"
                        + "而是「不代理方法」——Bean 定义照常存在，只是方法互调不再走容器");
    }

    private TierReport tier(String tier, Class<?> declaredConfig, String masterBean, String slaveBean, String note) {
        HandleMaster master = context.getBean(masterBean, HandleMaster.class);
        HandleSlave containerSlave = context.getBean(slaveBean, HandleSlave.class);
        Object configInstance = context.getBean(declaredConfig);
        String runtimeName = configInstance.getClass().getName();
        // 判断是否被增强只看类名是否还是声明的那个类：
        // 不匹配 CGLIB 后缀字符串，免得 Spring 改了后缀命名规则就误判
        return new TierReport(tier, runtimeName, !runtimeName.equals(declaredConfig.getName()),
                master.slaveIdentity(), System.identityHashCode(containerSlave),
                master.slave() == containerSlave, master.slave().serial(), containerSlave.serial(),
                HandleSlave.createdCount(tier), note);
    }
}
