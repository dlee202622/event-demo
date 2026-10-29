package com.example.demo.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.demo.startup.modes.ModeSnapshot;
import com.example.demo.startup.modes.StartupModesService;

/**
 * 场景 2 断言：三种启动模式的容器类型与服务器差异。
 *
 * <p>耗时项（约 5~10 秒）：本类会真的起三次子应用。放在这里是因为这三条结论
 * 是教程表格的数据源，必须每次构建都被验证。</p>
 */
@SpringBootTest
class StartupModesTest {

    @Autowired
    private StartupModesService modesService;

    /** 各模式的关键差异：容器类、有没有 WebServer、端口是否分配 */
    @Test
    void threeModesDifferAsDocumented() {
        Map<String, ModeSnapshot> byMode = modesService.compare().stream()
                .collect(Collectors.toMap(ModeSnapshot::mode, Function.identity()));

        ModeSnapshot none = byMode.get("NONE");
        assertThat(none.outcome()).isEqualTo("OK");
        // NONE 模式没有 Web 容器实现类，只有普通 AnnotationConfigApplicationContext
        assertThat(none.contextClass()).isEqualTo("AnnotationConfigApplicationContext");
        assertThat(none.webServer()).isNull();
        assertThat(none.port()).isNull();

        ModeSnapshot servlet = byMode.get("SERVLET");
        assertThat(servlet.contextClass()).contains("ServletWebServer");
        assertThat(servlet.webServer()).contains("Tomcat");
        // port=0 应被内核分配到一个正整数端口
        assertThat(servlet.port()).isPositive();

        ModeSnapshot reactive = byMode.get("REACTIVE");
        assertThat(reactive.contextClass()).contains("ReactiveWebServer");
        assertThat(reactive.webServer()).contains("Netty");

        // SERVLET 比 NONE 多出的 Bean 定义就是 Web 基础设施（DispatcherServlet、Tomcat 定制等）
        assertThat(servlet.beanCount()).isGreaterThan(none.beanCount());
    }

    /** 端口被占时子应用必须启动失败，且异常信息点名端口 */
    @Test
    void portInUseFailsStartup() {
        ModeSnapshot snapshot = modesService.portInUse();
        assertThat(snapshot.outcome()).isEqualTo("FAILED");
        assertThat(snapshot.failureMessage()).contains("already in use");
    }
}
