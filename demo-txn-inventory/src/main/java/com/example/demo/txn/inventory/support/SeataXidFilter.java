package com.example.demo.txn.inventory.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.seata.core.context.RootContext;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Seata XID 绑定过滤器：Seata 的 RPC 层不会自动穿越普通 HTTP 调用，
 * 全局事务 ID 必须由发起方塞进请求头（TX_XID），参与者在入口处绑定到线程上下文。
 * 绑定之后，本请求内的 @Transactional 方法才会被数据源代理识别为 Seata 分支。
 *
 * <p>为什么不自己写 Feign 拦截器：演示用最原始的「头字段 + 过滤器」，
 * 12 行代码说清 XID 传播的全部机制——Spring Cloud Alibaba 的
 * SeataFeignClient 做的也是同一件事，只是藏在封装里。</p>
 *
 * <p>坑：finally 里必须 unbind。Tomcat 线程会被复用，残留的 XID 会让
 * 下一个不相干请求被误判为某个全局事务的分支。</p>
 */
@Component
public class SeataXidFilter extends OncePerRequestFilter implements Ordered {

    /** Seata 官方约定的传播头名；发起方（A）的 RestClient 拦截器同名对应。 */
    public static final String XID_HEADER = "TX_XID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String xid = RootContext.getXID();
        // 优先取头字段；若本线程已有绑定（理论上不该发生）则保持原值
        String headerXid = request.getHeader(XID_HEADER);
        if (xid == null && headerXid != null && !headerXid.isEmpty()) {
            RootContext.bind(headerXid);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程复用场景下必须清理，否则残留 XID 污染下一个请求
            RootContext.unbind();
        }
    }

    /** 尽量靠前执行：业务控制器拿到请求时 XID 必须已绑定。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
