package com.example.demo.txn.order.support;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参与者调用客户端：把「对 B/C 的一次 HTTP 调用」标准化成带计时的 StepResult。
 *
 * <p>为什么不用 Feign/WebClient：教学目标是让读者看清每一次协议交互的
 * 请求-响应对，StepResult 保留原始响应体和延迟，协调者的返回就是一张
 * 逐跳的调用流水，不需要任何额外观测工具。</p>
 *
 * <p>失败语义：HTTP 4xx/5xx、连接失败、超时都折算成 ok=false 的 StepResult
 * 而不是抛异常——协调者必须「看见失败并做出决议」，这正是它与普通业务
 * 调用的差别；把异常吞进结果对象，决议代码才能写成直线。</p>
 */
@Component
public class ParticipantClient {

    /** 单次调用的完整观测记录：教程响应里 steps 数组的元素。 */
    public record StepResult(String service, String endpoint, boolean ok,
                             int statusCode, Object body, long latencyMs, String error) {
    }

    private final RestClient inventory;
    private final RestClient loyalty;

    public ParticipantClient(org.springframework.core.env.Environment env) {
        // 读超时 20 秒：必须大于 B/C 的 HANG 故障时长（默认 8s），
        // 否则「慢」会被误判成「不可达」，HANG 实验就观察不到了
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.inventory = RestClient.builder()
                .baseUrl(env.getProperty("demo.txn.order.inventory-base-url", "http://localhost:9009"))
                .requestFactory(factory)
                .build();
        this.loyalty = RestClient.builder()
                .baseUrl(env.getProperty("demo.txn.order.loyalty-base-url", "http://localhost:9010"))
                .requestFactory(factory)
                .build();
    }

    /** POST 调用参与者接口。queryParams 为 null/空时不带查询串。 */
    public StepResult post(String service, String path, Map<String, Object> queryParams) {
        RestClient client = "inventory".equals(service) ? inventory : loyalty;
        long begin = System.nanoTime();
        try {
            String body = client.post()
                    .uri(URI.create(buildUri(path, queryParams)))
                    .headers(this::attachXidIfAny)
                    .retrieve()
                    .body(String.class);
            long latency = (System.nanoTime() - begin) / 1_000_000;
            return new StepResult(service, path, true, 200, rawOrParsed(body), latency, null);
        } catch (RestClientResponseException e) {
            long latency = (System.nanoTime() - begin) / 1_000_000;
            // 4xx/5xx：参与者的业务拒绝（库存不足/故障注入）走这里，属于「协议内失败」
            return new StepResult(service, path, false, e.getStatusCode().value(),
                    rawOrParsed(e.getResponseBodyAsString()), latency, e.getMessage());
        } catch (Exception e) {
            long latency = (System.nanoTime() - begin) / 1_000_000;
            // 连接失败/超时：参与者可能宕机，属于「协议外失败」，2PC 实验的关键分支
            return new StepResult(service, path, false, 0, null, latency, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** GET 调用，用于查询参与者状态（教程的跨服务观测命令也用它）。 */
    public StepResult get(String service, String path) {
        RestClient client = "inventory".equals(service) ? inventory : loyalty;
        long begin = System.nanoTime();
        try {
            String body = client.get().uri(URI.create(path))
                    .headers(this::attachXidIfAny)
                    .retrieve().body(String.class);
            return new StepResult(service, path, true, 200, rawOrParsed(body),
                    (System.nanoTime() - begin) / 1_000_000, null);
        } catch (Exception e) {
            return new StepResult(service, path, false, 0, null,
                    (System.nanoTime() - begin) / 1_000_000,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Seata XID 传播：@GlobalTransactional 上下文里把全局事务 ID 塞进请求头，
     * 参与者的 SeataXidFilter 在入口绑定。非 Seata 调用时 XID 为 null，头不加——
     * 一个客户端同时服务五种方案，Seata 的痕迹只在 Seata 章节出现。
     */
    private void attachXidIfAny(org.springframework.http.HttpHeaders headers) {
        String xid = org.apache.seata.core.context.RootContext.getXID();
        if (xid != null && !xid.isEmpty()) {
            headers.set("TX_XID", xid);
        }
    }

    /** 保留 JSON 原文即可：嵌套 Map 的反序列化对教学没有增益，原文更接近真实协议。 */
    private Object rawOrParsed(String body) {
        return body == null ? null : body;
    }

    private String buildUri(String path, Map<String, Object> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path).append('?');
        boolean first = true;
        for (Map.Entry<String, Object> e : queryParams.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            // 参数都是 ASCII（商品号/数量/xid），不需要 percent-encoding
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }
}
