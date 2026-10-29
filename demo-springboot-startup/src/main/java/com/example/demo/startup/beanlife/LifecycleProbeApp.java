package com.example.demo.startup.beanlife;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 场景 4 的实验容器入口。
 *
 * <p>定位：@SpringBootApplication 自带 @ComponentScan，扫描根就是本包
 * {@code beanlife}——恰好覆盖 {@link LifecycleDemoBean}（含三个注入协作者）和
 * {@link LifecycleTraceProcessor}，又不会把 controller/service 等主应用组件拖进子容器。</p>
 *
 * <p>坑：实验用 NONE 模式起子容器（不占端口、不建 Web 层）。
 * 若这里被主应用扫描注册成第二个入口配置，也只是多一次同包扫描——
 * Bean 定义与主扫描完全重叠，Spring 按类名生成定义名，后注册者被忽略。</p>
 */
@SpringBootApplication
public class LifecycleProbeApp {
}
