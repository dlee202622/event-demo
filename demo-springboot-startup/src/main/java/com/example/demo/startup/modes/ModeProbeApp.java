package com.example.demo.startup.modes;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 场景 2 子应用的“空”入口：只有 {@code @SpringBootApplication}，没有任何业务 Bean。
 *
 * <p>定位：把它作为子 SpringApplication 的 sources，得到的容器是纯自动配置产物，
 * Bean 数量差值才能干净地归因于“模式不同”，而不是“扫描到的组件不同”。</p>
 *
 * <p>坑：它会被主应用的组件扫描顺带注册为配置类，但因为它不提供任何业务 Bean，
 * 无副作用；反过来，绝不能给它再套一层 @ComponentScan 指向业务包，
 * 否则子应用会重复注册控制器并抢占主应用之外的资源。</p>
 */
@SpringBootApplication
public class ModeProbeApp {
}
