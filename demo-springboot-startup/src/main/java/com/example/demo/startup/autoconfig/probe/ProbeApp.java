package com.example.demo.startup.autoconfig.probe;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 场景 3 排除实验的探针入口。
 *
 * <p>定位：与 {@link ProbeController} 同包，因此作为子应用 source 时，
 * 它继承的 @ComponentScan 恰好把探针控制器装进子容器——
 * 于是“排除 DispatcherServletAutoConfiguration”成为唯一变量：
 * 路由映射照常注册（WebMvcAutoConfiguration 还在），但没有人再接收 HTTP 请求。</p>
 *
 * <p>坑：这个类本身就是第二个 @SpringBootApplication。它会被主应用的组件扫描注册为
 * 配置类，但嵌套扫描范围就是本包，只多扫到一个 ProbeController（已由主扫描注册，
 * 定义相同、无害）；若把它挪离 probe 包，上述“同包即被扫到”的机制随之失效。</p>
 */
@SpringBootApplication
public class ProbeApp {
}
