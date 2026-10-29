package com.example.demo.startup.annotations.proxymethod;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 实验组：{@code @Configuration(proxyBeanMethods = false)}，即「lite 模式」。
 *
 * <p>定位：Boot 自己的自动配置类几乎全部标 {@code @AutoConfiguration}
 * （内部就是 {@code @Configuration(proxyBeanMethods = false)}），
 * 因为启动期少一次 CGLIB 生成子类就少一份开销。本类与
 * {@link ProxyMethodFullConfig} 代码逐行对齐，只有注解参数不同——
 * 控制变量做实验，读者才能把差异归因到那一个开关上。</p>
 *
 * <p>关键实现：{@code masterLite()} 里的 {@code slaveLite()} 是<b>真正的 Java 方法调用</b>，
 * 每次调用都 {@code new} 一个新对象；容器另外为 {@code slaveLite} 这个 Bean 定义
 * 再调一次方法，于是本 tier 的实例计数变成 2，且 master 持有的对象
 * 与容器里的 Bean 不是同一个。</p>
 *
 * <p>坑：① lite 模式下想依赖别的 Bean，请把它写成<b>方法参数</b>
 * （{@code @Bean HandleMaster masterLite(HandleSlave slave)}），容器会正确注入；
 * ② 用直接方法调用的写法在 lite 下不会报错，只会静默多出实例——
 * 缓存、连接池这类对象被复制时，问题往往在很久之后才暴露；
 * ③ {@code @Bean} 方法参数注入的顺序依赖与 full 模式一致，不受 proxyBeanMethods 影响。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ProxyMethodLiteConfig {

    /** lite 档的 slave：注意容器里的这个 Bean 与 master 内部 new 的那个不是同一个 */
    @Bean
    public HandleSlave slaveLite() {
        return new HandleSlave("lite");
    }

    /**
     * 直接调用 {@code slaveLite()}：lite 模式下这就是一次普通方法调用，
     * 返回值不进容器，也不复用单例。接口回显的 {@code sameInstance=false} 由此而来。
     */
    @Bean
    public HandleMaster masterLite() {
        return new HandleMaster("lite", slaveLite());
    }
}
