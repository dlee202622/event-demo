package com.example.demo.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.demo.startup.annotations.AnnotationClassMapService;
import com.example.demo.startup.annotations.AnnotationTreeService;
import com.example.demo.startup.annotations.BeanProvenanceService;
import com.example.demo.startup.annotations.ConditionFamilyService;
import com.example.demo.startup.annotations.FeatureDemoService;
import com.example.demo.startup.annotations.ProxyMethodService;
import com.example.demo.startup.annotations.SpiFileService;

/**
 * 场景 0 断言：注解与类的每一条映射都能在当前类路径上落地。
 *
 * <p>为什么这些断言值得写：它们检查的不是业务逻辑，而是<b>本文档的事实前提</b>——
 * Boot 版本一换，元注解构成、SPI 文件位置、条件类归属都可能变。
 * 测试挂了，说明教程里对应的那句话需要重新核对，这比读者自己撞上去早发现一步。</p>
 */
@SpringBootTest
class AnnotationMechanicsTest {

    @Autowired
    private AnnotationClassMapService classMapService;

    @Autowired
    private AnnotationTreeService treeService;

    @Autowired
    private SpiFileService spiFileService;

    @Autowired
    private ConditionFamilyService conditionService;

    @Autowired
    private BeanProvenanceService provenanceService;

    @Autowired
    private ProxyMethodService proxyMethodService;

    @Autowired
    private FeatureDemoService featureService;

    /** 对照表里所有「干活类」都必须在当前类路径上；missing 非空即文档与依赖漂移 */
    @Test
    void mapRowsPointToRealClasses() {
        AnnotationClassMapService.AnnotationMap map = classMapService.table();
        assertThat(map.missing()).isEmpty();
        assertThat(map.rowCount()).isGreaterThanOrEqualTo(15);
        assertThat(map.presentCount()).isEqualTo(map.rowCount());
    }

    /** @SpringBootApplication 必须仍然拆得出那三个元注解，且「出现次数」高于「唯一类型数」 */
    @Test
    void springBootApplicationSplitsIntoThree() {
        AnnotationTreeService.TreeReport report = treeService.report("");
        List<String> metaAnnotations = report.tree().stream()
                .filter(row -> row.depth() == 1)
                .map(row -> row.annotation())
                .toList();
        assertThat(metaAnnotations).contains(
                "org.springframework.boot.SpringBootConfiguration",
                "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                "org.springframework.context.annotation.ComponentScan");
        // 实测（本模块主类）：JDK 递归与 Spring 未去重流都是 12 条，去重后只剩 9 个类型。
        // 两条独立路径数出同一个「出现次数」，说明合并视图没有偷偷丢东西，只是会折叠重复
        assertThat(report.treeRows()).isEqualTo(report.mergedOccurrences());
        assertThat(report.mergedOccurrences()).isGreaterThan(report.mergedUniqueTypes());
        assertThat(report.mergedTypeCounts()).containsValue(2L);
        assertThat(report.countByStrategy()).containsKeys("DIRECT", "TYPE_HIERARCHY");
    }

    /**
     * SPI 清单：候选自动配置类来自多个 jar，本模块自己那份注册了两个实现，老键已退役。
     *
     * <p>实测数字：本模块只引 webmvc + webflux 两个 starter，
     * {@code ImportCandidates} 汇总出来是 <b>44</b> 个候选（Boot 4 把自动配置拆进了
     * spring-boot-webmvc / spring-boot-jackson / spring-boot-autoconfigure 等多个 jar，
     * 每个 jar 各带一份 .imports）。断言写 30 而不是 100，
     * 是因为「几百个候选」是 Boot 3 时代整包 autoconfigure 的印象，在 Boot 4 不成立。</p>
     */
    @Test
    void spiInventoryMatchesBootFourLayout() {
        SpiFileService.Inventory inventory = spiFileService.inventory();
        assertThat(inventory.candidateTotal()).isGreaterThan(30);
        assertThat(inventory.autoConfigImports()).isNotEmpty();
        assertThat(inventory.factoriesCarriesEnableAutoConfiguration()).isFalse();
        assertThat(inventory.ourFactories().keySet())
                .contains("org.springframework.boot.SpringApplicationRunListener",
                        "org.springframework.boot.diagnostics.FailureAnalyzer");
    }

    /** 条件注解家族：注解数多于 Condition 类数，且 OnClassCondition 确实服务两个注解 */
    @Test
    void conditionalFamilySharesConditionClasses() {
        ConditionFamilyService.ConditionFamily family = conditionService.family(true);
        assertThat(family.annotationCount()).isGreaterThan(family.distinctConditionClasses());
        ConditionFamilyService.ConditionRow onClass = family.rows().stream()
                .filter(row -> row.annotation().equals("ConditionalOnClass"))
                .findFirst().orElseThrow();
        assertThat(onClass.conditionClass())
                .isEqualTo("org.springframework.boot.autoconfigure.condition.OnClassCondition");
        assertThat(onClass.springBootCondition()).isTrue();
        ConditionFamilyService.ConditionRow onProperty = family.rows().stream()
                .filter(row -> row.annotation().equals("ConditionalOnProperty"))
                .findFirst().orElseThrow();
        // havingValue / matchIfMissing 是场景 3 开关的语义来源，必须在成员表里
        assertThat(onProperty.members()).contains("havingValue", "matchIfMissing");
    }

    /** 来源分类要能把「扫描产物」「自动配置导入」「@Bean 方法」「@Import 配置类」四者分开 */
    @Test
    void provenanceSeparatesRegistrationOrigins() {
        BeanProvenanceService.ProvenanceReport report = provenanceService.report();
        assertThat(report.totalDefinitions()).isGreaterThan(100);
        Map<String, Integer> byOrigin = report.byOrigin();
        assertThat(byOrigin.keySet()).anyMatch(key -> key.startsWith("组件扫描"));
        assertThat(byOrigin.keySet()).anyMatch(key -> key.startsWith("自动配置导入"));
        assertThat(byOrigin.keySet()).anyMatch(key -> key.startsWith("@Bean 方法"));
        // 四个探针类都在类路径上、都不在主容器里，否则 excludeFilters 就是空话
        assertThat(report.excludedProbeBeans()).hasSize(4);
        assertThat(report.excludedProbeBeans()).allMatch(text -> text.contains("容器里没有=已剔除"));
        assertThat(report.ourBeans()).anyMatch(row -> row.beanName().equals("featureEnableDemoConfig")
                && row.origin().startsWith("组件扫描"));
        assertThat(report.ourBeans()).anyMatch(row -> row.beanName().equals("startupFeatureApiBasic")
                && row.origin().startsWith("@Bean 方法"));
        // basic 配置被 excludeFilters 挡住了扫描，却仍由选择器装进来：
        // 它的 Bean 名是类全限定名，这就是 @Import 路线独有的签名
        assertThat(report.importedBySelector())
                .contains("com.example.demo.startup.annotations.feature.StartupFeatureBasicConfig");
        assertThat(report.ourBeans()).anyMatch(row -> row.beanName().endsWith("StartupFeatureBasicConfig")
                && row.origin().startsWith("@Import"));
    }

    /** proxyBeanMethods 的唯一差别落在「实例个数」和「引用是否同一个」上 */
    @Test
    void proxyBehaviourDiffersOnlyBySwitch() {
        ProxyMethodService.Experiment experiment = proxyMethodService.run();
        assertThat(experiment.full().sameInstance()).isTrue();
        assertThat(experiment.lite().sameInstance()).isFalse();
        // lite 每次方法互调都新建实例，因此同档计数必然高于 full
        assertThat(experiment.lite().instancesCreated())
                .isGreaterThan(experiment.full().instancesCreated());
        // full 被 CGLIB 增强（运行时类名≠声明类名），lite 没有
        assertThat(experiment.full().cglibEnhanced()).isTrue();
        assertThat(experiment.lite().cglibEnhanced()).isFalse();
    }

    /**
     * @EnableStartupFeature 全链路：选择器被调用、mode 决定导入哪个配置类、
     * full 档才出现属性 Bean，且命令行参数覆盖了 yaml（优先级 7 vs 2）。
     */
    @Test
    void enableAnnotationDrivesSelector() {
        FeatureDemoService.FeatureReport report = featureService.report();
        assertThat(report.selectorHistory())
                .anyMatch(call -> call.importingClass().endsWith("FeatureEnableDemoConfig")
                        && call.mode().equals("basic"))
                .anyMatch(call -> call.importingClass().endsWith("FeatureProbeApp")
                        && call.mode().equals("full"));
        assertThat(report.residentBasic().apiBeanNames()).contains("startupFeatureApiBasic");
        assertThat(report.residentBasic().propertiesBeanNames()).isEmpty();
        assertThat(report.childFull().propertiesBeanNames()).isNotEmpty();
        assertThat(report.childFull().boundValues()).anyMatch(value -> value.equals("retries=7"));
        assertThat(report.autoConfigSelectorIsDeferred()).isTrue();
    }
}
