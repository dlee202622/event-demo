package com.example.demo.startup.beanlife;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 场景 4 的“换脸”现场：BeanPostProcessor 在初始化之后把 Bean 替换成代理。
 *
 * <p>定位：{@code postProcessAfterInitialization} 的返回值会被容器直接采用——
 * 也就是说 {@code getBean()} 拿到的对象可以不是最初 new 的那个。
 * AOP（@EnableAspectJAutoProxy 背后的 AutoProxyCreator）正是在这一步动手，
 * 本类是它的最小化复刻。</p>
 *
 * <p>关键实现：只包装 {@link LifecycleDemoApi}，其余 Bean（含一堆 @Configuration）
 * 原样返回。教学演示不该改变框架自身的 Bean 图。</p>
 *
 * <p>实测顺序：本处理器的 before 早于 {@code @PostConstruct}，after 晚于 init-method——
 * 也就是说 before 能改属性、after 只能换实例，两个时机的能力完全不同。</p>
 *
 * <p>坑：① BPP 必须是无状态的，它对容器里每个 Bean 都会被调用一次；
 * ② 想改属性要放 before，想换实例只能放 after（after 拿到的是 init 完的对象）；
 * ③ JDK 动态代理只认接口——被包装的 Bean 若没有接口，getBean(具体类) 会直接
 * ClassCastException，这正是 Spring 对无接口类改用 CGLIB 的原因。</p>
 */
@Component
public class LifecycleTraceProcessor implements BeanPostProcessor {

    /** 被代理次数，用于报告“after 回调确实发生在每个演示实例上” */
    private static final AtomicInteger WRAPPED = new AtomicInteger();

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof LifecycleDemoBean demo) {
            LifecycleTraceRecorder.record(demo.instanceKey(), "bpp-before",
                    "postProcessBeforeInitialization：返回原对象（返回 null 会导致该 Bean 被丢弃）");
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof LifecycleDemoBean target)) {
            // 非演示 Bean 原样返回：必须克制，否则框架 Bean 全被包成代理
            return bean;
        }
        WRAPPED.incrementAndGet();
        String key = target.instanceKey();
        LifecycleTraceRecorder.record(key, "bpp-after",
                "postProcessAfterInitialization：返回 JDK 动态代理替换原对象（容器从此只认代理）");
        AtomicBoolean firstCall = new AtomicBoolean(true);
        return Proxy.newProxyInstance(bean.getClass().getClassLoader(),
                new Class<?>[] { LifecycleDemoApi.class },
                (proxyObject, method, args) -> {
                    // 只在第一次业务调用记一站，避免重复访问把 trace 灌满
                    if ("greet".equals(method.getName()) && firstCall.compareAndSet(true, false)) {
                        LifecycleTraceRecorder.record(key, "proxy-invoke",
                                "调用落在代理上（" + proxyObject.getClass().getSimpleName()
                                        + "），反射转发给目标对象 " + target.getClass().getSimpleName());
                    }
                    return method.invoke(target, args);
                });
    }

    /** 本 JVM 内被代理的演示 Bean 个数（主应用常驻 1 个 + 每次实验 1 个） */
    public static int wrappedCount() {
        return WRAPPED.get();
    }
}
