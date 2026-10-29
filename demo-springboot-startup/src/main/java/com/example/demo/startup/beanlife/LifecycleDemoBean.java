package com.example.demo.startup.beanlife;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 场景 4 的被解剖对象：一个同时挂满生命周期扩展点的 Bean。
 *
 * <p>定位：教科书把生命周期拆成“实例化 → 属性填充 → Aware → 初始化前后 → 使用 → 销毁”，
 * 本类把每一步落一条 {@link LifecycleTraceRecorder} 记录，
 * {@code GET /api/startup/beanlife/trace} 返回实测序列——站点名不带序号，
 * 真实顺序以返回的 seq 为准（这也避免注释与实测顺序打架）。</p>
 *
 * <p>关键顺序（本模块实测，2026-10-29，与很多二手教程不符）：
 * ① Aware 回调整体在属性填充之后、初始化之前；其中 BeanName/ClassLoader/BeanFactory
 * 由容器直接调用，ApplicationContext 由内置 BPP 在 before 阶段调用；
 * ② <b>用户自定义 BPP 的 before 早于 {@code @PostConstruct}</b>——实测顺序是
 * bpp-before → post-construct；常见说法“@PostConstruct 最先”在这里不成立，
 * 因为本 demo 的处理器在容器里注册得更靠前；
 * ③ init-method 之后才轮到 BPP.after 返回代理，容器最终存的是代理。</p>
 *
 * <p>坑：销毁三件套（@PreDestroy → DisposableBean → destroyMethod）只在容器
 * {@code close()} 时触发；应用还开着永远看不到，所以本 demo 每次实验都在
 * 独立子容器里新建实例并在结束时关掉它。</p>
 */
public class LifecycleDemoBean implements LifecycleDemoApi, BeanNameAware, BeanClassLoaderAware,
        BeanFactoryAware, ApplicationContextAware, InitializingBean, DisposableBean {

    private final String instanceKey;

    /** 构造器注入的协作者：实例化那一刻就绪，永不为 null */
    private final LifecycleNameService nameService;

    /** 字段注入：发生在构造之后，所以构造器里读它必为 null */
    @Autowired
    private LifecycleFieldDependency fieldDependency;

    private LifecycleSetterDependency setterDependency;

    /** 占位符注入：与字段/setter 同属 populateBean 阶段 */
    @Value("${demo.startup.beanlife.tag:default-tag}")
    private String tag;

    private String beanName;

    private String readyFlag;

    public LifecycleDemoBean(LifecycleNameService nameService) {
        this.instanceKey = LifecycleTraceRecorder.newInstanceKey();
        this.nameService = nameService;
        LifecycleTraceRecorder.record(instanceKey, "constructor",
                "实例化：构造注入 LifecycleNameService 完成；fieldDependency="
                        + (fieldDependency == null ? "null（字段注入还没发生）" : "非 null"));
    }

    @Autowired
    public void setSetterDependency(LifecycleSetterDependency setterDependency) {
        this.setterDependency = setterDependency;
        LifecycleTraceRecorder.record(instanceKey, "populate-bean",
                "属性填充：字段注入=" + (fieldDependency != null) + "，setter 注入完成，@Value tag=" + tag);
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
        // Aware 三连由 AbstractAutowireCapableBeanFactory#invokeAwareMethods 固定顺序调用：
        // BeanName → ClassLoader → BeanFactory（容器直接回调，不走 BPP 链）
        LifecycleTraceRecorder.record(instanceKey, "aware-bean-name", "beanName=" + name);
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        LifecycleTraceRecorder.record(instanceKey, "aware-classloader",
                "classLoader=" + classLoader.getClass().getSimpleName());
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        LifecycleTraceRecorder.record(instanceKey, "aware-bean-factory",
                "beanFactory=" + beanFactory.getClass().getSimpleName());
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        // Context/Environment 这一族由 ApplicationContextAwareProcessor（一个内置 BPP）
        // 在 before 阶段回调；实测它排在上面三个 Aware 之后、用户 BPP 之前
        LifecycleTraceRecorder.record(instanceKey, "aware-application-context",
                "context=" + applicationContext.getClass().getSimpleName()
                        + "（NONE 模式子容器应为 AnnotationConfigApplicationContext，与场景 2 对照）");
    }

    @PostConstruct
    public void postConstruct() {
        LifecycleTraceRecorder.record(instanceKey, "post-construct",
                "@PostConstruct：实测排在用户 BPP.before 之后（见类注释的顺序说明）");
    }

    @Override
    public void afterPropertiesSet() {
        LifecycleTraceRecorder.record(instanceKey, "initializing-bean",
                "InitializingBean.afterPropertiesSet：与 @PostConstruct 选一个用即可，混用时本回调在后");
    }

    /**
     * 由 {@code @Bean(initMethod = "customInitMethod")} 指定（见 LifecycleBeanConfig）。
     *
     * @return 非 null 会让容器改用返回值作为 Bean 实例；这里返回 null 保持原对象
     */
    public Object customInitMethod() {
        this.readyFlag = "READY";
        LifecycleTraceRecorder.record(instanceKey, "init-method",
                "@Bean(initMethod) 执行，readyFlag=" + readyFlag + "（初始化三件套到此结束）");
        return null;
    }

    /**
     * 第 14 号观察点：业务方法。若本实例被 BPP 包了代理，这条记录之前必相邻出现一条
     * {@code proxy-invoke}——两条连着就是“容器里存的是代理”的现场证据。
     */
    @Override
    public String greet() {
        LifecycleTraceRecorder.record(instanceKey, "target-method",
                "greet() 到达真实对象，readyFlag=" + readyFlag);
        return "hello from bean '" + beanName + "' via " + nameService.name()
                + " (fieldInjected=" + (fieldDependency != null)
                + ", setterInjected=" + (setterDependency != null)
                + ", tag=" + tag + ", ready=" + readyFlag + ")";
    }

    @Override
    public String beanName() {
        return beanName;
    }

    /** 实例标识，trace 过滤用 */
    public String instanceKey() {
        return instanceKey;
    }

    @PreDestroy
    public void preDestroy() {
        LifecycleTraceRecorder.record(instanceKey, "pre-destroy",
                "@PreDestroy：销毁三件套的第一个入口（与 @PostConstruct 同一个处理器成对处理）");
    }

    @Override
    public void destroy() {
        LifecycleTraceRecorder.record(instanceKey, "disposable-bean",
                "DisposableBean.destroy：接口回调，自定义 destroyMethod 在其后");
    }

    /** 由 {@code @Bean(destroyMethod = "customDestroyMethod")} 指定，实例轨迹终点 */
    public void customDestroyMethod() {
        LifecycleTraceRecorder.record(instanceKey, "destroy-method",
                "@Bean(destroyMethod) 执行完毕，本次实例生命周期闭合");
    }
}
