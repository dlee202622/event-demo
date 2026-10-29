package com.example.demo.startup.beanlife;

/**
 * 生命周期演示 Bean 的公开契约，供控制器经代理调用。
 *
 * <p>定位：BeanPostProcessor 返回的包装对象只能做 JDK 动态代理（依赖接口），
 * 没有这个接口控制器就只能拿到具体类、无法演示“容器里存的其实是我给的代理”。
 * 真实项目里这层接口通常是 Service 抽象。</p>
 */
public interface LifecycleDemoApi {

    /** 业务方法：第一次被调用时会额外记录一条“经代理访问”的站点 */
    String greet();

    /** 返回 beanName 注入值，验证 Aware 回调确实发生在实例可用之前 */
    String beanName();
}
