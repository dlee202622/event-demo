package com.example.demo.startup.beanlife;

import java.util.List;
import java.util.Map;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import com.example.demo.startup.beanlife.LifecycleTraceRecorder.StageEntry;

/**
 * 场景 4 的执行器：在一个短命子容器里完整跑一遍 Bean 生命周期并回收轨迹。
 *
 * <p>定位：常驻容器里的 Bean 销毁不了，想同时看到 @PreDestroy 这类尾部站点，
 * 唯一干净的办法是另起容器、用完关掉。子容器用 NONE 模式：不占端口、不建 Web 层，
 * 与主应用互不干扰。</p>
 *
 * <p>关键实现：
 * ① 每次实验先 {@code beginRun(marker)} 换实例标记，返回后按前缀过滤，
 * 保证读到的是这一次运行的站点而不是历史累计；
 * ② synchronized 串行化：静态收集器 + 全局标记决定了并发会串数据，
 * 教学场景宁可排队也不能给出错误序列；
 * ③ greet() 故意调用两次，用来证明 {@code proxy-invoke} 只记第一次。</p>
 *
 * <p>坑：销毁站点在 {@code context.close()} 里写入，必须在 close 之后再取 trace，
 * 顺序反了会少三条；另外子容器扫描 beanlife 包，与主应用扫描范围重叠，
 * 两边各自有一套同名单例，靠 instanceKey 区分。</p>
 */
@Service
public class LifecycleBeanLifeService {

    /** 一次生命周期实验的返回体 */
    public record LifecycleTrace(String runMarker, String instanceKey, int proxyWrappedTotal,
            String greetResult, List<StageEntry> stages, String reading) {
    }

    /**
     * 跑一次完整生命周期实验。
     *
     * @return stages 按 seq 升序，含从 constructor 到 destroy-method 的全部站点
     */
    public synchronized LifecycleTrace trace() {
        String marker = LifecycleTraceRecorder.beginRun("RUN-" + System.currentTimeMillis());
        SpringApplication application = new SpringApplication(LifecycleProbeApp.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("demo.startup.beanlife.tag", marker));
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        // 关 shutdown hook：本方法自己负责 close，避免退出时二次关闭
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run();
        String greet;
        String instanceKey;
        try {
            // 从子容器取 Bean：拿到的已经是代理（见 LifecycleTraceProcessor）。
            // 注意这里按接口取——若写成 getBean(LifecycleDemoBean.class) 会 ClassCastException，
            // 因为容器里存的代理只实现 LifecycleDemoApi，这正是 BPP 换脸的后果
            LifecycleDemoApi bean = context.getBean(LifecycleDemoApi.class);
            greet = bean.greet();
            // 第二次调用：证明 proxy-invoke 站点只记首次，target-method 每次都记
            bean.greet();
            instanceKey = instanceKeyOf(marker);
        }
        finally {
            // 销毁三件套在 close() 内写入，必须先关容器再取轨迹
            context.close();
        }
        return new LifecycleTrace(marker, instanceKey, LifecycleTraceProcessor.wrappedCount(), greet,
                LifecycleTraceRecorder.traceOf(instanceKey),
                // 顺序按实测写：bpp-before 在 post-construct 之前（@PostConstruct 由
                // InitDestroyAnnotationBeanPostProcessor 这个 BPP 触发，用户 BPP 排在它后面），
                // 这三处是初学者最容易记错的地方
                "seq 升序即真实执行顺序；aware-* 与 populate-bean 的相对位置、bpp-before 早于 post-construct、"
                        + "以及 proxy-invoke 紧邻 target-method，是三处最容易记错的地方");
    }

    /**
     * 子容器里那个演示 Bean 的 instanceKey。
     *
     * <p>标记规则是 {@code marker#序号}，本次实验新建的实例只有一个（NONE 子容器不重复创建单例），
     * 取匹配前缀的最大序号即为它。</p>
     */
    private String instanceKeyOf(String marker) {
        String found = null;
        for (StageEntry entry : LifecycleTraceRecorder.all()) {
            if (entry.instanceKey().startsWith(marker + "#")) {
                found = entry.instanceKey();
                // 不 break：若同前缀出现多个实例，以最后一个为准
            }
        }
        return found;
    }

    /** 主应用常驻实例的当前轨迹（不含销毁站点，因为进程还活着） */
    public List<StageEntry> residentTrace() {
        // 常驻实例是进程内第一个被创建的，序号为 1，标记为空串
        return LifecycleTraceRecorder.traceOf("#1");
    }
}
