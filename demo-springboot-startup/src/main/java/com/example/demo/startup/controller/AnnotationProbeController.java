package com.example.demo.startup.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.startup.annotations.AnnotationClassMapService;
import com.example.demo.startup.annotations.AnnotationTreeService;
import com.example.demo.startup.annotations.BeanProvenanceService;
import com.example.demo.startup.annotations.ConditionFamilyService;
import com.example.demo.startup.annotations.FeatureDemoService;
import com.example.demo.startup.annotations.ProxyMethodService;
import com.example.demo.startup.annotations.SpiFileService;

/**
 * 场景 0 的接口层：把「注解 → 类」这条链拆成七个可独立调用的观察点。
 *
 * <p>定位：读 Spring Boot 资料时最容易的问题是「说的我都懂，但看不到」。
 * 本控制器不做任何业务，只把框架自己的数据结构端出来：
 * 注解对照表、注解树、条件注解家族、SPI 文件清单、Bean 定义来源、
 * {@code proxyBeanMethods} 对照、{@code @EnableXxx} 实测。</p>
 *
 * <p>关键实现：全部是 GET 且无副作用（{@code /feature} 会临时起一个 NONE 子应用，
 * 但跑完即关，见 FeatureDemoService 的说明）。
 * 参数一律给默认值，读者复制 curl 就能出结果，不用先猜必填项。</p>
 *
 * <p>坑：① 这些接口读的都是<b>当前进程</b>的类路径与容器状态，
 * 换一个 classpath（例如少引一个 starter）数字就会变，这不是 bug 而是结论的前提；
 * ② {@code /tree} 的 {@code className} 传任意类都会被反射解析，
 * 因此对「类找不到」做了显式 400 处理——把栈抛给读者只会增加噪音；
 * ③ {@code /conditional} 与 {@code /spi} 会加载框架类，第一次调用略慢（几十毫秒级），
 * 之后由 JVM 的类缓存兜住。</p>
 */
@RestController
@RequestMapping("/api/startup/annotations")
public class AnnotationProbeController {

    private final AnnotationClassMapService classMapService;
    private final AnnotationTreeService treeService;
    private final ConditionFamilyService conditionService;
    private final SpiFileService spiFileService;
    private final BeanProvenanceService provenanceService;
    private final ProxyMethodService proxyMethodService;
    private final FeatureDemoService featureService;

    /** 七个服务各管一段链条，构造注入让依赖方向在类声明这一行就能读出来 */
    public AnnotationProbeController(AnnotationClassMapService classMapService,
            AnnotationTreeService treeService, ConditionFamilyService conditionService,
            SpiFileService spiFileService, BeanProvenanceService provenanceService,
            ProxyMethodService proxyMethodService, FeatureDemoService featureService) {
        this.classMapService = classMapService;
        this.treeService = treeService;
        this.conditionService = conditionService;
        this.spiFileService = spiFileService;
        this.provenanceService = provenanceService;
        this.proxyMethodService = proxyMethodService;
        this.featureService = featureService;
    }

    /**
     * 注解 → 类 对照表：本场景的目录页。
     *
     * <p>{@code activatesPresent} 是运行时实测的类路径检查，
     * {@code missing} 非空即说明表里某个类在当前依赖下不存在，需要重新核对文档。</p>
     */
    @GetMapping
    public AnnotationClassMapService.AnnotationMap map() {
        return classMapService.table();
    }

    /**
     * 注解树：递归展开某个类头上的注解及其元注解。
     *
     * @param className 目标类全限定名；留空即用本模块主类 {@code StartupApplication}
     */
    @GetMapping("/tree")
    public AnnotationTreeService.TreeReport tree(@RequestParam(defaultValue = "") String className) {
        return treeService.report(className);
    }

    /**
     * 条件注解家族表。
     *
     * @param includeUserCondition 是否把本模块自写的 {@code FeatureColorCondition} 追加为对照行
     */
    @GetMapping("/conditional")
    public ConditionFamilyService.ConditionFamily conditional(
            @RequestParam(defaultValue = "true") boolean includeUserCondition) {
        return conditionService.family(includeUserCondition);
    }

    /** SPI 文件清单：{@code AutoConfiguration.imports} 与 {@code spring.factories} 的实际内容与数量 */
    @GetMapping("/spi")
    public SpiFileService.Inventory spi() {
        return spiFileService.inventory();
    }

    /** Bean 定义来源分析：容器里每个定义分别是谁注册的 */
    @GetMapping("/provenance")
    public BeanProvenanceService.ProvenanceReport provenance() {
        return provenanceService.report();
    }

    /** {@code proxyBeanMethods} 对照实验：full 与 lite 两档的实测差异 */
    @GetMapping("/proxymethods")
    public ProxyMethodService.Experiment proxyMethods() {
        return proxyMethodService.run();
    }

    /** {@code @EnableStartupFeature} 实测：选择器历史 + basic/full 两档容器现状（会临时起一个子应用） */
    @GetMapping("/feature")
    public FeatureDemoService.FeatureReport feature() {
        return featureService.report();
    }

    /**
     * 参数校验的落点：把「类名写错」变成可读的 400，而不是一屏堆栈。
     *
     * <p>为什么只兜 {@code IllegalArgumentException}：这是唯一由读者输入触发的失败
     * （{@code /tree?className=...}）。框架内部异常继续冒泡，
     * 免得把真 bug 伪装成「你用错了」。</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                        "hint", "className 必须是本模块运行时已加载的类，例如 "
                                + "com.example.demo.startup.StartupApplication"));
    }
}
