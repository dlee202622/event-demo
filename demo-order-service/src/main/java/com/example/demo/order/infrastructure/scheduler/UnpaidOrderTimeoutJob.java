package com.example.demo.order.infrastructure.scheduler;

import com.example.demo.order.application.OrderExpiryService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 超时未支付订单清理任务。
 *
 * <p>在调度中心配置 JobHandler = {@code unpaidOrderTimeoutJob} 即可按 cron 触发。</p>
 */
@Component
@EnableConfigurationProperties(OrderExpiryProperties.class)
public class UnpaidOrderTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderTimeoutJob.class);

    private final OrderExpiryService orderExpiryService;
    private final OrderExpiryProperties properties;

    public UnpaidOrderTimeoutJob(OrderExpiryService orderExpiryService,
                                 OrderExpiryProperties properties) {
        this.orderExpiryService = orderExpiryService;
        this.properties = properties;
    }

    @XxlJob("unpaidOrderTimeoutJob")
    public void cancelExpiredOrders() {
        int cancelled = orderExpiryService.cancelExpired(
                properties.getUnpaidTimeout(), properties.getExpiryBatchSize());

        String message = "超时未支付取消 " + cancelled + " 笔（阈值 " + properties.getUnpaidTimeout() + "）";
        XxlJobHelper.log(message);
        log.info("[XXL-JOB] {}", message);
    }
}
