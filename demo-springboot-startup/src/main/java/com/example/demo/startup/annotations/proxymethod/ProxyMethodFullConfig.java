package com.example.demo.startup.annotations.proxymethod;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对照组：默认的 {@code @Configuration}（{@code proxyBeanMethods = true}，即「full 模式」）。
 *
 * <p>定位：Spring 会在启动期用 CGLIB 给这个类生成子类代理，拦掉所有 {@code @Bean} 方法调用，
 * 让「方法互调」变成「按 Bean 名字从容器取」。这是 {@code @Configuration} 与
 * {@code @Component} 最本质的区别，也是初学者最容易忽略的一层字节码增强。</p>
 *
 * <p>关键实现：{@code masterFull()} 里那句 {@code slaveFull()} 看着像普通 Java 调用，
 * 实际被代理改写为 {@code beanFactory.getBean("slaveFull")}；
 * 所以 master 拿到的 slave 与容器里注册的 {@code slaveFull} Bean 是同一个对象，
 * 且 {@code slaveFull()} 方法体只执行一次（{@code HandleSlave.createdCount("full") == 1}）。</p>
 *
 * <p>坑：① full 模式的类不能是 {@code final}，方法也不能是 {@code private}/{@code final}，
 * 否则 CGLIB 无法覆盖，Bean 方法互调会静默退化成普通调用；
 * ② 代理只在容器里的那个实例上生效——自己 {@code new ProxyMethodFullConfig()} 再调方法，
 * 行为等同于 lite。</p>
 */
@Configuration
public class ProxyMethodFullConfig {

    /** 容器单例 slave：本 tier 只应有 1 个实例 */
    @Bean
    public HandleSlave slaveFull() {
        return new HandleSlave("full");
    }

    /**
     * master 依赖同配置类的 slave。
     *
     * <p>这里的 {@code slaveFull()} 是「被代理的方法调用」，返回容器单例，
     * 而不是新建对象——这就是 {@code proxyBeanMethods=true} 的全部含义。</p>
     */
    @Bean
    public HandleMaster masterFull() {
        return new HandleMaster("full", slaveFull());
    }
}
