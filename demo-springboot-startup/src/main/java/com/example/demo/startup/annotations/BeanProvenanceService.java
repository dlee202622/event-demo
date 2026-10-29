package com.example.demo.startup.annotations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

/**
 * Bean 定义来源分析：容器里这一百多个定义，分别是<b>谁</b>注册的。
 *
 * <p>定位：场景 1 的时间线已经给出「6 → 174」这个跳变，但「跳变之后每个 Bean 从哪来」
 * 才是把注解和类对上的关键一步。本服务遍历 {@code getBeanDefinitionNames()}，
 * 用三个可靠信号给每个定义打来源标签：</p>
 * <ol>
 *   <li>{@code factoryMethodName} 非空 → 它是 {@code @Bean} 方法的产物
 *       （注册者是 {@code ConfigurationClassBeanDefinitionReader}）；</li>
 *   <li>类名命中 {@link ImportCandidates} 的候选清单 → 自动配置导入；</li>
 *   <li>定义的实现类是 {@code ScannedGenericBeanDefinition} → 组件扫描产物。</li>
 * </ol>
 *
 * <p>关键实现：只认「定义对象的运行时类型名」和 {@link BeanDefinition} 上的公开字段，
 * 不去反射 Spring 的包私有内部类（{@code ConfigurationClassBeanDefinition} 就是包私有的，
 * 编译期引用不到）。这样 Boot 小版本升级时本服务不会突然编译失败，
 * 最差只是分类落到「其它」里——读者看得见，我们就有的解释。</p>
 *
 * <p>坑：① 分类是「运行时证据」而不是「设计意图」，例如 {@code @Import} 直接导入的配置类
 * 也会被 {@code ConfigurationClassBeanDefinitionReader} 注册，与 {@code @Bean} 方法产物
 * 同名同类型，靠 {@code factoryMethodName} 才能分开；
 * ② 框架自己注册的基础设施 Bean（{@code internalConfigurationAnnotationProcessor} 等）
 * 没有来源注解，会落进「其它」，这不是分类失败；
 * ③ {@code excludeFilters} 的效果在这里最直观：四个探针类<b>不在</b>主容器里，
 * 但它们 {@code Class.forName} 得到——「类路径上有、容器里没有」正是过滤器的定义。</p>
 */
@Service
public class BeanProvenanceService {

    /** Spring 的 CGLIB 增强类名标记（实测形如 {@code ProxyMethodFullConfig$$SpringCGLIB$$0}） */
    private static final String ENHANCED_MARK = "$$SpringCGLIB$$";

    /** 主应用用 excludeFilters 剔除的四个子应用入口（Bean 名按首字母小写的默认规则推） */
    private static final List<String> EXCLUDED_PROBE_BEANS = List.of(
            "modeProbeApp", "lifecycleProbeApp", "probeApp", "featureProbeApp");

    /** 探针类的真实类名：用来证明「类路径上有，只是没进容器」 */
    private static final List<String> EXCLUDED_PROBE_CLASSES = List.of(
            "com.example.demo.startup.modes.ModeProbeApp",
            "com.example.demo.startup.beanlife.LifecycleProbeApp",
            "com.example.demo.startup.autoconfig.probe.ProbeApp",
            "com.example.demo.startup.annotations.feature.FeatureProbeApp");

    /**
     * 一个定义的来源标签。
     *
     * @param beanClass 解析出的类型名（{@code @Bean} 方法取返回类型，类级注册取自身）
     * @param definitionClass 注册用的 BeanDefinition 实现类简单名（运行时证据）
     * @param origin 分类结论：组件扫描 / 自动配置导入 / @Bean 方法 / @Import 配置类 / 其它
     * @param cglibEnhanced 类型名里是否带 {@code $$SpringCGLIB$$}——即该配置类走了 full 模式
     * @param source 框架记录的定义来源（扫描时是 class 文件资源，可能为 null）
     */
    public record ProvenanceRow(String beanName, String beanClass, String definitionClass,
            String origin, boolean cglibEnhanced, String source) {
    }

    /**
     * 来源全貌。
     *
     * @param byOrigin 每种来源的定义数量（读者最常问的就是「自动配置到底给了我多少个」）
     * @param byDefinitionClass 按 BeanDefinition 实现类聚合的数量
     * @param ourBeans 本模块自己的 Bean（包名 com.example.demo.startup）逐条列出
     * @param excludedProbeBeans 四个探针类的容器内/类路径状态
     * @param importedBySelector 名字等于「类全限定名」且来源为 @Import 的配置类——
     *            这就是 {@code @EnableStartupFeature} 的选择器真的跑过的证据
     */
    public record ProvenanceReport(int totalDefinitions, Map<String, Integer> byOrigin,
            Map<String, Integer> byDefinitionClass, List<ProvenanceRow> ourBeans,
            List<String> excludedProbeBeans, List<String> importedBySelector,
            int autoConfigCandidates, String reading) {
    }

    private final ConfigurableApplicationContext context;

    /** 注入主容器：遍历定义、解析 @Bean 方法返回类型、查 excludeFilters 的效果都只能在真实容器上做 */
    public BeanProvenanceService(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** 生成报告；候选清单通过 {@link ImportCandidates} 取，与自动配置选择器同一份数据 */
    public ProvenanceReport report() {
        String[] names = context.getBeanDefinitionNames();
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, context.getClassLoader())
                .getCandidates();
        Map<String, Integer> byOrigin = new LinkedHashMap<>();
        Map<String, Integer> byDefinitionClass = new LinkedHashMap<>();
        List<ProvenanceRow> ours = new ArrayList<>();
        List<String> importedBySelector = new ArrayList<>();
        for (String name : names) {
            BeanDefinition definition = context.getBeanFactory().getBeanDefinition(name);
            String definitionClass = definition.getClass().getSimpleName();
            String registeredClass = definition.getBeanClassName();
            // @Bean 方法的定义上 beanClassName 为 null：真实类型只能问容器（getType 会解析工厂方法返回类型）。
            // 不问它，本模块一半的 Bean 都会从 ourBeans 里消失——第一版就是这么漏掉的
            Class<?> resolved = context.getType(name);
            String beanClass = resolved != null ? resolved.getName()
                    : (registeredClass != null ? registeredClass : name);
            String origin = classify(name, definition, baseName(beanClass), baseName(registeredClass), candidates);
            byOrigin.merge(origin, 1, Integer::sum);
            byDefinitionClass.merge(definitionClass, 1, Integer::sum);
            if (beanClass.startsWith("com.example.demo.startup")) {
                ours.add(new ProvenanceRow(name, shortClass(beanClass), definitionClass, origin,
                        beanClass.contains(ENHANCED_MARK), sourceOf(definition)));
                if (origin.startsWith("@Import")) {
                    // 被 @Import（含 ImportSelector）注册的配置类，Bean 名就是类全限定名——
                    // 与扫描产物（首字母小写的短名）形成可直接对照的证据
                    importedBySelector.add(name);
                }
            }
        }
        List<String> excluded = new ArrayList<>();
        for (int i = 0; i < EXCLUDED_PROBE_BEANS.size(); i++) {
            String beanName = EXCLUDED_PROBE_BEANS.get(i);
            boolean onClasspath = isLoadable(EXCLUDED_PROBE_CLASSES.get(i));
            boolean inContainer = context.containsBeanDefinition(beanName);
            // 只把「在类路径上但不在容器里」的报告出来，这才是 excludeFilters 的证据
            excluded.add(beanName + (inContainer ? "（仍在容器！过滤器没生效）"
                    : "（类路径上有=" + onClasspath + "，容器里没有=已剔除）"));
        }
        return new ProvenanceReport(names.length, byOrigin, byDefinitionClass, ours, excluded,
                importedBySelector, candidates.size(),
                "byOrigin 回答「谁注册了我」；byDefinitionClass 回答「用什么定义对象注册」；"
                        + "cglibEnhanced 顺手回答「这个配置类走的是 full 还是 lite」——"
                        + "本模块的 proxyMethodLiteConfig 是少数几个 false 之一，"
                        + "而 @Bean 方法产物的 false 只说明返回类型没被增强，与配置类模式无关。"
                        + "importedBySelector 用类全限定名当 Bean 名，与扫描产物的短名形成对照："
                        + "excludeFilters 挡住了扫描，@Import 照样把它装了进来");
    }

    /**
     * 分类规则，按可靠性排序。
     *
     * <p>先判 {@code factoryMethodName}：{@code @Bean} 方法一定有它，
     * 而类级注册（扫描/导入）一定没有——这一刀最干净。再判候选清单命中，
     * 因为自动配置类本身也是配置类，若先按实现类名分会和扫描产物混在一起。</p>
     *
     * <p>最后那条「Bean 名 == 类全限定名」是 {@code @Import} 的指纹，实测得出：
     * 组件扫描走 {@code AnnotationBeanNameGenerator}，产物是首字母小写的短名
     * （{@code startupFeatureBasicConfig}）；而 {@code @Import} / {@code ImportSelector}
     * 交出来的配置类由 {@code ConfigurationClassBeanDefinitionReader} 以<b>全限定名</b>注册，
     * 定义对象是 {@code AnnotatedGenericBeanDefinition}。
     * 只看定义类名分不出它，只看名字又可能误伤显式 register，所以两个条件一起判。</p>
     *
     * @param beanName 容器里的 Bean 名（判断 {@code @Import} 指纹用）
     * @param registeredClass 定义上登记的类名，{@code @Bean} 方法为 null
     */
    private String classify(String beanName, BeanDefinition definition, String resolvedClass,
            String registeredClass, List<String> candidates) {
        // getFactoryMethodName() 是 BeanDefinition 接口上的公开方法，
        // 不需要向下转型到 AbstractBeanDefinition（那个内部类在 Boot 小版本间动过）
        if (definition.getFactoryMethodName() != null) {
            return "@Bean 方法（ConfigurationClassBeanDefinitionReader）";
        }
        // 自动配置类被登记时类名可能带 CGLIB 后缀，先归一再比对，否则整桶都会漏判
        String candidateKey = registeredClass != null ? baseName(registeredClass) : resolvedClass;
        if (candidates.contains(candidateKey)) {
            return "自动配置导入（.imports → DeferredImportSelector）";
        }
        if ("ScannedGenericBeanDefinition".equals(definition.getClass().getSimpleName())) {
            return "组件扫描（@ComponentScan）";
        }
        if ("ConfigurationClassBeanDefinition".equals(definition.getClass().getSimpleName())) {
            return "@Import 导入的配置类";
        }
        // 实测：@Import 直接导入（含 ImportSelector 返回的类名）拿到的是 AnnotatedGenericBeanDefinition，
        // 且 Bean 名就是类全限定名——本模块的 StartupFeatureBasicConfig 正是这一类
        if ("AnnotatedGenericBeanDefinition".equals(definition.getClass().getSimpleName())
                && beanName.equals(candidateKey)) {
            return "@Import 导入的配置类";
        }
        if (resolvedClass.startsWith("org.springframework.")) {
            return "框架基础设施";
        }
        return "其它（显式 register / 内联注册）";
    }

    /** 去掉 CGLIB 增强后缀，拿到用户声明的那个类名 */
    private String baseName(String className) {
        if (className == null) {
            return "";
        }
        int mark = className.indexOf(ENHANCED_MARK);
        return mark < 0 ? className : className.substring(0, mark);
    }

    /** 展示用：包名压成简单类名，但保留增强标记，它本身就是信息 */
    private String shortClass(String className) {
        String base = baseName(className);
        int dot = base.lastIndexOf('.');
        String simple = dot < 0 ? base : base.substring(dot + 1);
        return className.contains(ENHANCED_MARK) ? simple + ENHANCED_MARK + "0" : simple;
    }

    private String sourceOf(BeanDefinition definition) {
        // getSource() 来自 BeanMetadataElement（BeanDefinition 的父接口），同样无需向下转型
        Object source = definition.getSource();
        if (source == null) {
            return "（无）";
        }
        String text = String.valueOf(source);
        // 扫描产物的 source 是完整资源路径，长到会淹没表格；只留最后一段文件名。
        // 形如 resource: class path resource [com/example/.../XxxConfig.class] —— 尾部那个 ] 要一起切掉
        int slash = text.lastIndexOf('/');
        String tail = slash > 0 ? text.substring(slash + 1) : text;
        return tail.endsWith("]") ? tail.substring(0, tail.length() - 1) : tail;
    }

    private boolean isLoadable(String className) {
        try {
            // initialize=false：探针类带着 @SpringBootApplication，真初始化会触发注解解析，
            // 这里只需要证明「类文件在类路径上」
            Class.forName(className, false, context.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
