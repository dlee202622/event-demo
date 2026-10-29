package com.example.demo.startup.autoconfig.probe;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场景 3 排除实验的探针路由。
 *
 * <p>定位：与 {@link ProbeApp} 同包，因此探针子应用一定装载这条路由。
 * baseline 实测 200；排除 DispatcherServletAutoConfiguration 的那次压根没启动成功，
 * 连端口都不监听——预想的“路由在、无人接收 → 404”在 Boot 4.1.2 没有出现，
 * 因为 {@code ErrorMvcAutoConfiguration} 依赖被排除者提供的 {@code DispatcherServletPath}。</p>
 *
 * <p>坑：包位置是实验的控制变量。把本类挪出 probe 包，子应用就扫不到它，
 * baseline 也会 404，对比失去意义；主应用因为扫描整个 startup 包同样能路由到它。</p>
 */
@RestController
@RequestMapping("/api/startup/probe")
public class ProbeController {

    /** 存活探针：任何正常启动的上下文（主应用 / baseline 子应用）都返回 pong */
    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }
}
