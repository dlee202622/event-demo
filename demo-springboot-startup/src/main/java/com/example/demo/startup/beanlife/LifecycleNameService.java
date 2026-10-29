package com.example.demo.startup.beanlife;

/**
 * 构造器注入协作者（场景 4）。
 *
 * <p>定位：生命周期站点需要一个“真依赖”才能证明实例化阶段注入已完成。
 * 单独成文件是为了能从 {@code @Bean} 方法签名里直接引用。</p>
 */
public class LifecycleNameService {

    String name() {
        return "lifecycle-service";
    }
}
