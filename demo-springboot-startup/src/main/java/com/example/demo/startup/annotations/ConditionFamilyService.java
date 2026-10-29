package com.example.demo.startup.annotations;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnCheckpointRestore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnCloudPlatform;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnJava;
import org.springframework.boot.autoconfigure.condition.ConditionalOnJndi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingFilterBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWarDeployment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWarDeployment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.stereotype.Service;

import com.example.demo.startup.autoconfig.FeatureColorCondition;

/**
 * 条件注解家族：把「{@code @ConditionalOnXxx} 到底是哪个类在判定」一次讲清。
 *
 * <p>定位：初学者把 {@code @ConditionalOnClass} 当成「Spring 自己懂一件事」，
 * 实际上它只是 {@code @Conditional(OnClassCondition.class)} 的别名——
 * 每个条件注解头上都挂着同一个 {@link Conditional}，判定逻辑全在那个 Condition 类里。
 * 本服务用反射把这张「注解 → Condition 类」的表打印出来，
 * 顺便证明另一个事实：二十多个条件注解其实只落到十来个 Condition 类上，
 * 家族比想象中小得多。</p>
 *
 * <p>关键实现：注解清单是<b>编译期引用</b>而不是字符串——少一个类就编译不过，
 * 所以这份表天然跟着 Boot 版本走，不会出现「教程里写了 Boot 3 有、Boot 4 没有的注解」。
 * Condition 类通过 {@code MergedAnnotations.from(注解类型).get(Conditional.class)} 取出，
 * 用的是和框架解析用户类时同一套 API（场景 3 的自定义 {@code @Conditional} 也这么查）。</p>
 *
 * <p>坑：① {@code @ConditionalOnProperty} 的 {@code value} 与 {@code name} 是别名，
 * 且<b>没有</b> {@code matchIfMissing} 之外的默认值语义：属性不存在时默认判定为不匹配
 * （要匹配得显式写 {@code matchIfMissing = true}）；
 * ② {@code @ConditionalOnBooleanProperty} 是 Boot 4 新拆出来的，
 * 专门处理「属性值必须是 true/false」的场景，别和 {@code OnPropertyCondition} 的
 * 「值不等于 false」语义混用；③ 条件类的 {@code getMatchOutcome} 只在自动配置时被调用一次，
 * 同一个 {@code @Bean} 方法上的条件不会随请求重跑，改属性必须重启进程；
 * ④ 多个 {@code @Conditional} 同时存在时全部通过才装配，
 * 顺序由 {@code @Order} 决定，但「谁先判定」不影响结果，只影响报告的观感。</p>
 */
@Service
public class ConditionFamilyService {

    /**
     * 一行映射。
     *
     * @param conditionClass 真正干活的 Condition 类全限定名
     * @param members 该注解的成员名（按声明顺序），例如 {@code value/name/havingValue/matchIfMissing}
     * @param springBootCondition Condition 类是否继承 {@link SpringBootCondition}
     *            （继承它才能白拿「条件日志 + 结构化消息」，见场景 3 的 FeatureColorCondition）
     */
    public record ConditionRow(String annotation, String conditionClass, List<String> members,
            boolean springBootCondition, String semantics) {
    }

    /**
     * 家族全貌。
     *
     * @param distinctConditionClasses 去重后的 Condition 类个数——小于注解个数就是本表最想给的数字
     */
    public record ConditionFamily(int annotationCount, int distinctConditionClasses,
            Map<String, List<String>> conditionToAnnotations, List<ConditionRow> rows, String reading) {
    }

    /** 一句话语义表：只写「判定输入是什么」，不写实现细节，避免与框架源码漂移 */
    private static final Map<Class<? extends Annotation>, String> SEMANTICS = semantics();

    /** 每个注解的判定语义；缺项时返回通用描述，保证表格不会缺行 */
    private static Map<Class<? extends Annotation>, String> semantics() {
        Map<Class<? extends Annotation>, String> map = new LinkedHashMap<>();
        map.put(ConditionalOnClass.class, "类路径上存在指定类（value/name 指定 Class 或类名字符串）");
        map.put(ConditionalOnMissingClass.class, "类路径上不存在指定类名");
        map.put(ConditionalOnBean.class, "容器里已存在指定 Bean（受 search / type / annotationClass 影响）");
        map.put(ConditionalOnMissingBean.class, "容器里不存在指定 Bean——自动配置「用户没配才我配」的标准写法");
        map.put(ConditionalOnSingleCandidate.class, "指定类型恰好一个候选，或多个候选里有一个 @Primary");
        map.put(ConditionalOnMissingFilterBean.class, "Servlet 过滤器类没有可匹配的 Bean");
        map.put(ConditionalOnProperty.class, "属性存在且值不等于 false（havingValue 可自定义匹配规则）");
        map.put(ConditionalOnBooleanProperty.class, "属性值必须是布尔字面量 true/false，缺省视为不匹配");
        map.put(ConditionalOnProperties.class, "多个属性条件全部满足（复数形式，逐条列出 prefix/name）");
        map.put(ConditionalOnBooleanProperties.class, "多个布尔属性条件全部满足");
        map.put(ConditionalOnWebApplication.class, "当前是 Web 应用（type 可细分 SERVLET / REACTIVE）");
        map.put(ConditionalOnNotWebApplication.class, "当前不是 Web 应用");
        map.put(ConditionalOnWarDeployment.class, "以 WAR 形式部署在外部容器");
        map.put(ConditionalOnNotWarDeployment.class, "不是 WAR 部署（内嵌容器场景）");
        map.put(ConditionalOnJndi.class, "指定 JNDI 位置可查到");
        map.put(ConditionalOnJava.class, "运行时 JDK 版本满足 range 约束");
        map.put(ConditionalOnCloudPlatform.class, "运行在指定云平台（如 CloudFoundry）");
        map.put(ConditionalOnThreading.class, "当前虚拟线程 / 平台线程模式与期望一致");
        map.put(ConditionalOnExpression.class, "SpEL 表达式求值为 true");
        map.put(ConditionalOnResource.class, "指定 classpath:/file: 资源存在");
        map.put(ConditionalOnCheckpointRestore.class, "支持 CRIU 检查点恢复的运行时环境");
        return map;
    }

    /** 编译期固定的清单：Boot 版本里缺哪个类就编译不过，表格因此不会「过期」 */
    private static final List<Class<? extends Annotation>> ANNOTATIONS = List.of(
            ConditionalOnClass.class, ConditionalOnMissingClass.class,
            ConditionalOnBean.class, ConditionalOnMissingBean.class,
            ConditionalOnSingleCandidate.class, ConditionalOnMissingFilterBean.class,
            ConditionalOnProperty.class, ConditionalOnBooleanProperty.class,
            ConditionalOnProperties.class, ConditionalOnBooleanProperties.class,
            ConditionalOnWebApplication.class, ConditionalOnNotWebApplication.class,
            ConditionalOnWarDeployment.class, ConditionalOnNotWarDeployment.class,
            ConditionalOnJndi.class, ConditionalOnJava.class, ConditionalOnCloudPlatform.class,
            ConditionalOnThreading.class, ConditionalOnExpression.class, ConditionalOnResource.class,
            ConditionalOnCheckpointRestore.class);

    /**
     * 生成家族表。
     *
     * @param includeUserCondition 是否把本模块自己写的 {@link FeatureColorCondition}
     *            作为对照行放在末尾（默认 true）
     */
    public ConditionFamily family(boolean includeUserCondition) {
        List<ConditionRow> rows = new ArrayList<>();
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        // 根行手工给：@Conditional 自己头上没有 @Conditional，反射查它会拿到「未出现」
        rows.add(new ConditionRow("（根）@Conditional", Conditional.class.getName(), List.of("value"), false,
                "真正的机制在这里：容器把 value 里的每个 Condition 类都实例化并调 matches()，"
                        + "全部返回 true 才装配这个配置类/Bean 方法"));
        for (Class<? extends Annotation> annotationType : ANNOTATIONS) {
            ConditionRow row = rowOf(annotationType, annotationType.getSimpleName(),
                    SEMANTICS.getOrDefault(annotationType, "由 Condition 类自定义判定"));
            rows.add(row);
            reverse.computeIfAbsent(row.conditionClass(), key -> new ArrayList<>())
                    .add(row.annotation());
        }
        if (includeUserCondition) {
            // 对照组：用户侧不需要发明 @ConditionalOnXxx，直接 @Conditional(自己的类) 就是一条新规则
            rows.add(new ConditionRow("（本模块自定义）@Conditional(FeatureColorCondition)",
                    FeatureColorCondition.class.getName(), List.of("自定义类，无注解成员"),
                    SpringBootCondition.class.isAssignableFrom(FeatureColorCondition.class),
                    "读属性 demo.startup.autoconfig.feature-color，值包含 green 即 match——见场景 3"));
        }
        // 最大桶按实测算，不写死「某类管几个」——Boot 版本一变数字就漂
        Map.Entry<String, List<String>> biggest = reverse.entrySet().stream()
                .max(java.util.Comparator.comparingInt(entry -> entry.getValue().size()))
                .orElse(null);
        String biggestText = biggest == null ? ""
                : "，最大桶是 " + simpleNameOf(biggest.getKey()) + " 管 " + biggest.getValue().size() + " 个注解";
        Set<String> distinct = new LinkedHashSet<>(reverse.keySet());
        return new ConditionFamily(ANNOTATIONS.size(), distinct.size(), reverse, rows,
                "注解 " + ANNOTATIONS.size() + " 个，落到 Condition 类 " + distinct.size() + " 个"
                        + biggestText + "；conditionToAnnotations 反查最直观——"
                        + "「一个 Condition 类服务多个注解」就是家族比想象中小得多的原因");
    }

    private String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /**
     * 单个注解的映射行。
     *
     * @throws IllegalStateException 注解头上没有 {@code @Conditional}（说明 Boot 改了实现，宁可报错也不静默漏行）
     */
    private ConditionRow rowOf(Class<? extends Annotation> annotationType,
            String displayName, String semantics) {
        Conditional conditional = MergedAnnotations.from(annotationType, SearchStrategy.DIRECT)
                .get(Conditional.class).synthesize();
        if (conditional == null) {
            throw new IllegalStateException(annotationType.getName() + " 头上没有 @Conditional，"
                    + "说明框架实现已变化，请重新核对本表");
        }
        Class<?> conditionClass = conditional.value()[0];
        List<String> members = Arrays.stream(annotationType.getDeclaredMethods())
                .map(Method::getName)
                .toList();
        return new ConditionRow(displayName, conditionClass.getName(), members,
                SpringBootCondition.class.isAssignableFrom(conditionClass), semantics);
    }
}
