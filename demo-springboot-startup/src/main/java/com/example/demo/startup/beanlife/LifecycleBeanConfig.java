package com.example.demo.startup.beanlife;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 场景 4 的装配声明：用 {@code @Bean(initMethod/destroyMethod)} 注册演示 Bean。
 *
 * <p>定位：初始化/销毁的第三种写法（自定义方法名）只能在这里表达——
 * {@code @Component} 注解没有 initMethod 属性。把它做成独立配置类，
 * 主应用和子容器扫描本包时都会装配，实验与常驻实例行为一致。</p>
 *
 * <p>坑：① {@code destroyMethod} 默认值 "(inferred)" 已经能自动找到 close/shutdown，
 * 显式写名字是为了让第 3 种销毁写法可观测，不是必需；
 * ② 三个协作者必须是独立 @Bean，否则构造/字段注入拿不到对象，
 * 生命周期站点会缺证据。</p>
 */
@Configuration(proxyBeanMethods = false)
public class LifecycleBeanConfig {

    /**
     * 演示 Bean 的唯一注册点。
     *
     * @param nameService 构造注入协作者，作为方法参数由容器解析
     * @return 尚未初始化的实例；init/destroy 由属性声明、容器在正确时机反射调用
     */
    @Bean(initMethod = "customInitMethod", destroyMethod = "customDestroyMethod")
    public LifecycleDemoBean lifecycleDemoBean(LifecycleNameService nameService) {
        // 注意：new 发生在 @Bean 方法体内，容器的“实例化”站点日志由构造器自己记录，
        // 这里不做额外埋点，避免把“工厂方法返回”误当成“实例化完成”
        return new LifecycleDemoBean(nameService);
    }

    /** 构造注入协作者 */
    @Bean
    public LifecycleNameService lifecycleNameService() {
        return new LifecycleNameService();
    }

    /** 字段注入协作者 */
    @Bean
    public LifecycleFieldDependency lifecycleFieldDependency() {
        return new LifecycleFieldDependency();
    }

    /** setter 注入协作者 */
    @Bean
    public LifecycleSetterDependency lifecycleSetterDependency() {
        return new LifecycleSetterDependency();
    }
}
