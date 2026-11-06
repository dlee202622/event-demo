package com.example.demo.txn.loyalty.support;

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
 * Seata XID 绑定过滤器（与 B 对称）：全局事务 ID 经请求头 TX_XID 传播，
 * 参与者在入口绑定到线程上下文，@Transactional 才会被识别为 Seata 分支。
 * finally 里 unbind 是硬性要求——Tomcat 线程复用会放大任何上下文残留。
 */
@Component
public class SeataXidFilter extends OncePerRequestFilter implements Ordered {

    /** Seata 官方约定的传播头名；发起方（A）的 RestClient 拦截器同名对应。 */
    public static final String XID_HEADER = "TX_XID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String xid = RootContext.getXID();
        String headerXid = request.getHeader(XID_HEADER);
        // 只在本线程无 XID 时绑定：防止意外的双重绑定覆盖现场
        if (xid == null && headerXid != null && !headerXid.isEmpty()) {
            RootContext.bind(headerXid);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            RootContext.unbind();
        }
    }

    /** 尽量靠前执行：业务控制器拿到请求时 XID 必须已绑定。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
