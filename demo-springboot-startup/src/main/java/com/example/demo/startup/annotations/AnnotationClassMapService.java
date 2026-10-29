package com.example.demo.startup.annotations;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

/**
 * 注解 → 类 对照表：本模块的「从注解讲起」就落在这张表上。
 *
 * <p>定位：学 Spring Boot 最常见的两个卡点，都是「只记住了注解名，不知道谁在干活」——
 * 于是 {@code @ConditionalOnClass} 写错了不知道该找谁调试，
 * {@code @EnableAutoConfiguration} 换包了也不知道影响哪一步。
 * 本服务把十几个高频注解逐个对上「真正执行工作的类」，并当场用反射检查
 * 这些类<b>此刻是否在这台机器的类路径上</b>（{@code activatesPresent} 字段）。
 * 为什么要现检而不是抄文档：Boot 4 把不少类挪了模块
 * （例如 {@code @ConditionalOnThreading} 这类新条件、jdbc 相关自动配置整体搬家），
 * 网上教程里的包名经常已经失效，一检就知道。</p>
 *
 * <p>关键实现：每行只声明「注解 FQN」和「干活类 FQN」两个字符串，
 * 剩下的都是检查与格式化。表是静态的、检查是动态的，
 * 这样读者改一行代码就能看到 {@code activatesPresent} 从 true 变 false。</p>
 *
 * <p>坑：① CGLIB 生成的 {@code @Configuration} 子类不在表里——它启动时才存在，
 * 类名形如 {@code XxxConfiguration$$SpringCGLIB$$0}，见 {@code /annotations/proxymethods}；
 * ② 「注解点亮多个类」是常态（一个 {@code @EnableXxx} 常常带出 3 个 Bean），
 * 表里只写主责类，其余在该行的 {@code role} 里点名；
 * ③ 有些行不是注解（spring.factories 那一行），因为「Boot 的入口不全是注解」
 * 这一点本身就是必须讲清楚的事实。</p>
 */
@Service
public class AnnotationClassMapService {

    /**
     * 一行对照。
     *
     * @param entry 读者手上的东西：注解名（或 SPI 文件名）
     * @param entryFqn 其全限定名；SPI 文件写资源路径
     * @param activatesClass 被它点亮、真正干活的类
     * @param role 那个类的职责，一句话
     * @param observeVia 在本模块哪条接口/场景能亲眼看见这条链
     * @param activatesPresent 运行时类路径上是否真有这个类（实测字段）
     */
    public record AnnotationMapRow(String entry, String entryFqn, String activatesClass, String role,
            String observeVia, boolean activatesPresent) {
    }

    /** 表 + 统计：{@code missing} 非空说明 Boot 版本或依赖又变了，需要重对文档 */
    public record AnnotationMap(int rowCount, int presentCount, List<AnnotationMapRow> rows,
            List<String> missing, String reading) {
    }

    /** 原始定义：activatesClass 允许是逗号分隔的多个类，逐个检查后合并成布尔 */
    private record Definition(String entry, String entryFqn, String activatesClass, String role, String observeVia) {
    }

    /**
     * 表的原始定义。
     *
     * <p>分成六组，顺序就是启动时被点亮的顺序（装配 → 条件 → 配置类 → @Enable → 生命周期 → 非注解入口），
     * 读者从上往下读一遍，等于把 Boot 的装配链走了一遍。</p>
     */
    private static final List<Definition> DEFINITIONS = List.of(
            // 组 1 · 装配主链：这三个注解决定「容器里到底有哪些类」，是 @SpringBootApplication 的三合一本体
            new Definition("@SpringBootConfiguration", "org.springframework.boot.SpringBootConfiguration",
                    "org.springframework.context.annotation.Configuration",
                    "元注解层：它本身就是 @Configuration 的特化，用来标记「唯一主配置类」",
                    "/annotations/tree"),
            new Definition("@EnableAutoConfiguration", "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                    "org.springframework.boot.autoconfigure.AutoConfigurationImportSelector",
                    "DeferredImportSelector：读 .imports、按条件筛选、排序，推迟到所有用户配置解析完再展开",
                    "/annotations/spi + 场景 3 条件报告"),
            // @AutoConfiguration 是「被导入的那一边」的标记：它同时告诉框架别用 full 模式增强配置类
            new Definition("@AutoConfiguration", "org.springframework.boot.autoconfigure.AutoConfiguration",
                    "org.springframework.boot.autoconfigure.AutoConfigurationPackages",
                    "自动配置类专用标记（内含 proxyBeanMethods=false），并让框架知道「基础包」在哪",
                    "场景 3 /annotations/spi"),
            // 组 2 · 扫描与过滤：扫描器负责「多加」，两个内置过滤器负责「别多加」，两者共同决定 Bean 定义集合
            new Definition("@ComponentScan", "org.springframework.context.annotation.ComponentScan",
                    "org.springframework.context.annotation.ClassPathBeanDefinitionScanner",
                    "把包路径下的类逐个变成 Bean 定义（扫描的执行人）",
                    "/annotations/provenance"),
            // CUSTOM 类型的过滤器才是排除逻辑的落点；Boot 抢先把这两个过滤器塞进了每个 @ComponentScan
            new Definition("@ComponentScan.Filter(CUSTOM)", "org.springframework.context.annotation.ComponentScan.Filter",
                    "org.springframework.boot.context.TypeExcludeFilter,org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter",
                    "Boot 自带两个过滤器：前者跳过被用户显式排除的类型，后者防止自动配置类被当普通组件重复注册",
                    "/annotations/provenance"),
            // @Conditional 是所有 @ConditionalOnXxx 的底层；条件不满足时类连定义都不会注册，等于从未存在
            new Definition("@Conditional", "org.springframework.context.annotation.Conditional",
                    "org.springframework.boot.autoconfigure.condition.OnClassCondition",
                    "所有条件注解的底层：容器逐个实例化 Condition 类并调 matches()",
                    "/annotations/conditional"),
            // 组 3 · 配置类内部：@Import 决定「还要解析哪些类」，@Bean 决定「方法怎么变成 Bean」
            new Definition("@Import", "org.springframework.context.annotation.Import",
                    "org.springframework.context.annotation.ConfigurationClassParser",
                    "解析 @Import 的四种目标（配置类 / ImportSelector / DeferredImportSelector / Registrar）并分派",
                    "/annotations/feature"),
            // factoryMethodName 是 @Bean 产物唯一的指纹，来源分类就靠它把方法级注册和类级注册分开
            new Definition("@Bean", "org.springframework.context.annotation.Bean",
                    "org.springframework.context.annotation.ConfigurationClassBeanDefinitionReader",
                    "把方法变成带 factoryMethodName 的 Bean 定义——这就是来源分类能认出它的依据",
                    "/annotations/provenance"),
            new Definition("@Configuration(proxyBeanMethods)", "org.springframework.context.annotation.Configuration",
                    "org.springframework.context.annotation.ConfigurationClassEnhancer",
                    "full 模式给配置类生成 CGLIB 子类，把 @Bean 方法互调改成「从容器取单例」",
                    "/annotations/proxymethods"),
            // 组 4 · @Enable 系与属性绑定：注解零逻辑，全靠 @Import 点名一个 Registrar 或配置类
            // 注意绑定后处理器不是注解带来的，是自动配置注册的——这是「@ConfigurationProperties 不生效」的头号原因
            new Definition("@ConfigurationProperties",
                    "org.springframework.boot.context.properties.ConfigurationProperties",
                    "org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor",
                    "Bean 创建后把 Environment 里的值灌进字段；它由自动配置注册，不随注解本身出现",
                    "/annotations/feature（full 档）"),
            new Definition("@EnableConfigurationProperties",
                    "org.springframework.boot.context.properties.EnableConfigurationProperties",
                    "org.springframework.boot.context.properties.EnableConfigurationPropertiesRegistrar",
                    "Registrar 负责注册属性 Bean 与绑定后处理器——实测该注解头上就写着 @Import(它)",
                    "/annotations/feature（full 档）"),
            new Definition("@EnableScheduling（@EnableXxx 通例）",
                    "org.springframework.scheduling.annotation.EnableScheduling",
                    "org.springframework.scheduling.annotation.SchedulingConfiguration",
                    "@Enable 系注解的标准形状：注解自己零逻辑，全靠头上的 @Import 点名一个配置类",
                    "/annotations/feature（本模块手写同款）"),
            // 组 5 · 实例化之后：生命周期回调和占位符替换都不是容器硬编码，而是普通的 BeanPostProcessor / BFPP
            new Definition("@PostConstruct / @PreDestroy", "jakarta.annotation.PostConstruct",
                    "org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor",
                    "生命周期回调不是容器硬编码，而是这个 BPP 在 postProcessBeforeInitialization 里触发的",
                    "场景 4 打点顺序"),
            new Definition("@Value", "org.springframework.beans.factory.annotation.Value",
                    "org.springframework.context.support.PropertySourcesPlaceholderConfigurer",
                    "占位符解析器；由 PropertyPlaceholderAutoConfiguration 注册，属于「你没写但自动配置给了」的那类",
                    "场景 3 / 本模块首页端口回显"),
            new Definition("@RestController + @GetMapping", "org.springframework.web.bind.annotation.RestController",
                    "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping",
                    "把注解方法登记成 URL→方法的映射表，交给 DispatcherServlet 分发",
                    "本模块全部接口"),
            // 组 6 · 非注解入口：容器还不存在时，框架只能靠读文件找到实现类，这就是 SPI 的意义
            new Definition("META-INF/spring.factories（不是注解）",
                    "META-INF/spring.factories",
                    "org.springframework.core.io.support.SpringFactoriesLoader",
                    "容器还不存在时就要用的东西走这里：SpringApplicationRunListener、FailureAnalyzer",
                    "场景 1 / 场景 5 + /annotations/spi"),
            new Definition("FailureAnalyzer（接口）", "org.springframework.boot.diagnostics.FailureAnalyzer",
                    "org.springframework.boot.diagnostics.AbstractFailureAnalyzer",
                    "启动失败时把异常翻译成 DESCRIPTION/ACTION；本模块自建了一个，另含内置端口冲突分析器",
                    "场景 5（需重启进程）"));

    private final ConfigurableApplicationContext context;

    /** 注入主容器：类路径检查必须用「应用真实使用的那个 ClassLoader」，用自己 new 的会漏掉嵌套 jar */
    public AnnotationClassMapService(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** 生成对照表；逗号分隔的多个类只要有一个加载不到就记进 missing，方便一眼看出文档漂了 */
    public AnnotationMap table() {
        List<AnnotationMapRow> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Definition definition : DEFINITIONS) {
            boolean allPresent = true;
            for (String className : definition.activatesClass().split(",")) {
                String normalized = className.trim().replace('$', '.');
                if (!isLoadable(normalized)) {
                    allPresent = false;
                    missing.add(definition.entry() + " → " + normalized);
                }
            }
            rows.add(new AnnotationMapRow(definition.entry(), definition.entryFqn(),
                    definition.activatesClass(), definition.role(), definition.observeVia(), allPresent));
        }
        return new AnnotationMap(rows.size(), (int) rows.stream().filter(AnnotationMapRow::activatesPresent).count(),
                rows, missing,
                "读法：先看 entry（你写的注解），再看 activatesClass（谁在干活），最后看 observeVia（去哪条接口验证）。"
                        + "activatesPresent=false 的行说明当前类路径缺该模块，需要加依赖或换 Boot 版本说法");
    }

    private boolean isLoadable(String className) {
        try {
            // initialize=false：只确认可加载，不触发静态初始化（自动配置类静态块会读环境）
            Class.forName(className, false, context.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
