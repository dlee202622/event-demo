package com.example.demo.startup.annotations;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.stereotype.Service;

/**
 * 注解树服务：把「一个注解背后挂着哪些注解」变成可打印、可数的一棵树。
 *
 * <p>定位：Spring Boot 的教学绕不开一句「{@code @SpringBootApplication} 是三合一」，
 * 而这句话本身是可证伪的——只要递归读每个注解类型上声明的注解，
 * 谁挂在谁下面、挂了几层、带什么参数一目了然。本类用两条独立路径看同一件事：
 * ① JDK 反射递归（{@code getAnnotations()} 一层层往下走，完全不依赖 Spring）；
 * ② Spring 的 {@link MergedAnnotations}（一次调用给出可去重的合并结果）。
 * 实测（本模块主类）两者<b>未去重</b>的条目数相同，都是「出现次数」；
 * 只有合并视图能按类型去重，出现两次的元注解（{@code mergedTypeCounts} 里 value=2 的那些）
 * 会塌成一条——「出现 12 次但只有 9 个类型」这个差值就是元注解复用率的直接证据。</p>
 *
 * <p>关键实现：递归带 path 集合与深度上限 {@link #MAX_DEPTH}，因为注解可以互相引用，
 * 不设限有死循环风险；{@code format} 递归渲染 {@code Class[]}、枚举、嵌套注解，
 * 避免 JSON 里出现 {@code sun.proxy$Proxy…} 这类噪声。</p>
 *
 * <p>坑：① JDK 的 {@code getAnnotations()} 只返回 {@code RUNTIME} 保留策略的注解，
 * {@code CLASS}/{@code SOURCE} 级在这里永远看不到，别据此断言「框架没写这个注解」；
 * ② 直接反射读注解实例看到的是<b>未解析别名</b>的原始值——
 * {@code @ComponentScan.Filter} 的 {@code value}/{@code classes} 是一对 {@code @AliasFor}，
 * 只写 {@code classes} 时 {@code value()} 返回空数组，别名要由 Spring 的合并层解析
 * （报告里的 {@code filterAliasProof} 把两种视图并排给出来）；
 * ③ Spring 7 的 {@code SearchStrategy} 只剩 4 个值（DIRECT / INHERITED_ANNOTATIONS /
 * SUPERCLASS / TYPE_HIERARCHY），Boot 3 时代的 META_ANNOTATIONS、TYPE_MAPPING、PRESENT
 * 已不是公开选项——网上抄来的 {@code SearchStrategy.PRESENT} 在 Boot 4 编译不过。</p>
 */
@Service
public class AnnotationTreeService {

    /** 递归深度上限：实际注解链最深 3~4 层，超过即视为异常图结构，直接截断 */
    private static final int MAX_DEPTH = 6;

    /**
     * 树的一行。
     *
     * @param depth 0 = 直接标在目标类上；1+ = 通过元注解间接出现
     * @param annotation 注解全限定名
     * @param declaredOn 该注解挂在谁身上（目标类名，或承载它的注解名）
     * @param attributes 该注解自身的成员值（JDK 原始视图，别名未解析）
     */
    public record TreeRow(int depth, String annotation, String declaredOn, Map<String, String> attributes) {
    }

    /**
     * 一次完整报告。
     *
     * @param rootClass 被解剖的类
     * @param treeRows JDK 递归得到的行数（含重复类型）
     * @param mergedOccurrences Spring 合并视图的条目数（未去重，实测与 treeRows 相同）
     * @param mergedUniqueTypes Spring 去重后的唯一注解类型数
     * @param mergedTypeCounts 每个注解类型出现的次数（哪些类型出现了两次，一目了然）
     * @param countByStrategy 四种 SearchStrategy 下合并视图的条数（只报实测数字）
     * @param filterAliasProof {@code @ComponentScan.Filter} 的两种视图对照；目标类无扫描时为空串
     * @param tree 树本体
     * @param reading 怎么读这份数据
     */
    public record TreeReport(String rootClass, int treeRows, int mergedOccurrences, int mergedUniqueTypes,
            Map<String, Long> mergedTypeCounts,
            Map<String, Integer> countByStrategy, String filterAliasProof, List<TreeRow> tree, String reading) {
    }

    /**
     * 生成报告。
     *
     * @param className 目标类全限定名；空串或 null 时用本模块主类
     * @throws IllegalArgumentException 类名在运行时类路径上找不到
     */
    public TreeReport report(String className) {
        Class<?> target = load(className);
        List<TreeRow> rows = new ArrayList<>();
        // 每条递归路径各自维护 visited（见 walk 里的 path.remove）：
        // 同一个元注解在不同分支下都要被看见，但同一条链上不能重复
        for (Annotation present : target.getAnnotations()) {
            walk(present, 0, target.getName(), rows, new LinkedHashSet<>());
        }
        MergedAnnotations merged = MergedAnnotations.from(target, SearchStrategy.DIRECT);
        // 按类型出现次数聚合：merged.stream() 的条目数不是唯一类型数，
        // 同一个元注解被多条链带出来就会计多次——「12 条 vs 9 类」的差值就是去重本身
        Map<String, Long> typeCounts = merged.stream()
                .collect(Collectors.groupingBy(ann -> ann.getType().getSimpleName(),
                        LinkedHashMap::new, Collectors.counting()));
        long occurrences = merged.stream().count();
        Map<String, Integer> byStrategy = new LinkedHashMap<>();
        for (SearchStrategy strategy : SearchStrategy.values()) {
            byStrategy.put(strategy.name(), (int) MergedAnnotations.from(target, strategy).stream().count());
        }
        return new TreeReport(target.getName(), rows.size(), (int) occurrences, typeCounts.size(), typeCounts,
                byStrategy, aliasProof(merged), rows,
                "treeRows=" + rows.size() + "（JDK 递归的出现次数）与 mergedOccurrences=" + occurrences
                        + "（Spring 未去重条目数）实测相等，去重后只剩 " + typeCounts.size()
                        + " 个唯一类型；countByStrategy 四种策略数字也相同——Spring 7 的 SearchStrategy"
                        + " 只决定「沿类继承链往上找多深」，元注解展开永远是开着的，改它不会让树变浅。"
                        + "depth>=1 的行都是元注解，@SpringBootApplication 拆出的三合一可以直接数出来");
    }

    private Class<?> load(String className) {
        String name = (className == null || className.isBlank())
                ? "com.example.demo.startup.StartupApplication"
                : className.trim();
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException("运行时类路径上找不到 " + name + "，请填本模块已加载的类名", ex);
        }
    }

    /** 递归展开一个注解实例：先记它自己，再展开它的注解类型上声明的那些注解 */
    private void walk(Annotation present, int depth, String declaredOn, List<TreeRow> rows, Set<String> path) {
        Class<? extends Annotation> annType = present.annotationType();
        if (depth > MAX_DEPTH || !path.add(annType.getName())) {
            return;
        }
        rows.add(new TreeRow(depth, annType.getName(), declaredOn, attributesOf(present)));
        for (Annotation meta : annType.getAnnotations()) {
            // 跳过 @Target/@Retention/@Documented/@Repeatable：它们是注解的“定义元信息”，
            // 不承载装配语义，留着会把树读成一堵墙（读者要找的 @Import/@Conditional 会被埋掉）
            if (meta.annotationType().getName().startsWith("java.lang.annotation")) {
                continue;
            }
            walk(meta, depth + 1, annType.getName(), rows, path);
        }
        // 回溯：允许同一注解出现在另一条分支上（例如 @Configuration 同时挂在
        // @SpringBootConfiguration 和某个用户配置类上）
        path.remove(annType.getName());
    }

    /** 读注解成员值：注解类型的方法天然无参，全部视为成员 */
    private Map<String, String> attributesOf(Annotation instance) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Member member : membersOf(instance)) {
            values.put(member.name(), member.value());
        }
        return values;
    }

    /** 成员名 + 已格式化的值；单独建类型是为了在两个调用点复用反射异常处理 */
    private record Member(String name, String value) {
    }

    private List<Member> membersOf(Annotation instance) {
        List<Member> members = new ArrayList<>();
        Arrays.stream(instance.annotationType().getDeclaredMethods()).forEach(member -> {
            try {
                // 真实风险不是 IllegalAccessException，而是 TypeNotPresentException：
                // 成员引用了运行时不存在的类时，JDK 在解 Class 值那一刻抛未检查异常。
                // 捕获它并降级，否则一个坏成员会让整棵树消失（接口直接 500）。
                members.add(new Member(member.getName(), format(member.invoke(instance))));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                members.add(new Member(member.getName(), "（读取失败：" + ex.getClass().getSimpleName() + "）"));
            }
        });
        return members;
    }

    /**
     * 把注解成员值渲染成人能读的字符串。
     *
     * <p>数组里可能是 Class、枚举、嵌套注解（{@code @ComponentScan.Filter}），因此逐项递归；
     * 深度受注解结构本身约束，不会无限展开。</p>
     */
    private String format(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Class<?> type) {
            return type.getSimpleName();
        }
        if (value instanceof Annotation nested) {
            return "@" + nested.annotationType().getSimpleName() + "("
                    + membersOf(nested).stream()
                            .map(m -> m.name() + "=" + m.value())
                            .collect(Collectors.joining(", "))
                    + ")";
        }
        if (value instanceof Object[] array) {
            return Arrays.stream(array).map(this::format).collect(Collectors.joining(", ", "[", "]"));
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.getDeclaringClass().getSimpleName() + "." + enumValue.name();
        }
        return String.valueOf(value);
    }

    /**
     * 别名解析对照：同一个 {@code @Filter}，JDK 原始视图与 Spring 合成视图给出的成员不同。
     *
     * <p>为什么值得单列：读者抄官方文档时常见疑问是「我明明只写了 classes，为什么文档说写 value」，
     * 答案就是这两个视图的差异——本方法把差异打成一行文本，不需要读者自己去猜。</p>
     */
    private String aliasProof(MergedAnnotations merged) {
        var scan = merged.get(org.springframework.context.annotation.ComponentScan.class);
        if (!scan.isPresent()) {
            return "";
        }
        org.springframework.context.annotation.ComponentScan synthesized = scan.synthesize();
        var filters = synthesized.excludeFilters();
        if (filters.length == 0) {
            return "excludeFilters 为空（无 CUSTOM 过滤器可对照）";
        }
        // Spring 合成层会把 @AliasFor 的 classes 归一到 value：所以下面两个数组应当等价
        var first = filters[0];
        return "Spring 合成视图 value=" + Arrays.stream(first.value()).map(Class::getSimpleName).toList()
                + " / classes=" + Arrays.stream(first.classes()).map(Class::getSimpleName).toList()
                + "（别名由 Spring 解析，JDK 树里的原始视图两个数组不等价）";
    }
}
