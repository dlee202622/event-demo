package com.example.demo.order.infrastructure.scheduler;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器配置。
 *
 * <p>执行器启动后会向调度中心注册，调度中心按任务配置回调本进程的 9999 端口。
 * 本地调度中心跑在 Docker 里时，回调地址使用 host.docker.internal。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "xxl.job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobSpringExecutor(
            @Value("${xxl.job.admin.addresses}") String adminAddresses,
            @Value("${xxl.job.access-token:}") String accessToken,
            @Value("${xxl.job.executor.appname}") String appName,
            @Value("${xxl.job.executor.address:}") String address,
            @Value("${xxl.job.executor.ip:}") String ip,
            @Value("${xxl.job.executor.port:9999}") int port,
            @Value("${xxl.job.executor.logpath:./logs/xxl-job}") String logPath,
            @Value("${xxl.job.executor.logretentiondays:30}") int logRetentionDays) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appName);
        executor.setAddress(address);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
