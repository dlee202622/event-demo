package com.example.demo.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.demo.startup.beanlife.LifecycleBeanLifeService;
import com.example.demo.startup.beanlife.LifecycleTraceRecorder.StageEntry;

/**
 * 场景 4 断言：Bean 生命周期站点的真实顺序。
 *
 * <p>这个测试是整份教程里“注释与实测冲突”时的裁判：教程正文写的顺序一律以这里为准。</p>
 */
@SpringBootTest
class BeanLifecycleTest {

    @Autowired
    private LifecycleBeanLifeService lifecycleService;

    /** 一次实验的站点序列（站点名去掉实例相关后缀后按 seq 排列） */
    @Test
    void stagesFollowDocumentedOrder() {
        List<String> stages = lifecycleService.trace().stages().stream().map(StageEntry::stage).toList();
        assertThat(stages).containsExactly(
                "constructor",
                "populate-bean",
                "aware-bean-name",
                "aware-classloader",
                "aware-bean-factory",
                "aware-application-context",
                "bpp-before",
                "post-construct",
                "initializing-bean",
                "init-method",
                "bpp-after",
                "proxy-invoke",
                // greet() 被调用两次：proxy-invoke 只记首次（CAS 守卫），target-method 每次都记
                "target-method",
                "target-method",
                "pre-destroy",
                "disposable-bean",
                "destroy-method");
    }

    /** 实验串行执行两次，序列必须完全一致（可重复性是教学 demo 的硬要求） */
    @Test
    void secondRunProducesSameSequence() {
        List<String> first = lifecycleService.trace().stages().stream().map(StageEntry::stage).toList();
        List<String> second = lifecycleService.trace().stages().stream().map(StageEntry::stage).toList();
        assertThat(second).isEqualTo(first);
    }

    /** 代理证据：容器返回的对象不是原始类，且 greet() 仍能得到真实结果 */
    @Test
    void beanIsProxiedButStillWorks() {
        LifecycleBeanLifeService.LifecycleTrace trace = lifecycleService.trace();
        assertThat(trace.greetResult()).contains("hello from bean").contains("ready=READY");
        assertThat(trace.proxyWrappedTotal()).isGreaterThanOrEqualTo(1);
    }
}
