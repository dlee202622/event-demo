package com.example.demo.startup.annotations;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.stereotype.Service;

/**
 * SPI 文件清单：Spring Boot「注解 → 类」链条的最后一环，其实不是注解而是<b>文件</b>。
 *
 * <p>定位：自动配置类不是被组件扫描扫出来的，而是从每个 jar 的
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 里读出来的；而 {@code SpringApplicationRunListener}、{@code FailureAnalyzer} 这类
 * <b>容器还不存在时就要用到的东西</b>，仍然走老的 {@code META-INF/spring.factories}。
 * 本模块两个文件都用了（场景 1 与场景 5），所以这张对照表既是原理，也是本仓库的工程事实。</p>
 *
 * <p>关键实现：用 {@code classLoader.getResources(...)} 遍历<b>全部</b>同名资源，
 * 才能回答「几百个自动配置类是从哪几个 jar 汇聚来的」；候选总数则直接调框架真正使用的入口
 * {@link ImportCandidates#load}，它负责合并与去重——
 * {@code AutoConfigurationImportSelector} 看到的就是同一份名单。</p>
 *
 * <p>坑：① {@code AutoConfiguration.imports} 一行一个类名，{@code #} 起注释、空行忽略，
 * 但<b>写错类名没有任何提示</b>：它只是进不了候选集合，条件报告里也查不到，属于静默失效；
 * ② {@code spring.factories} 的续行符是行尾反斜杠，漏写会把下一行当新键解析，
 * 表现为「自监听器装了但没生效」；
 * ③ Boot 3 → Boot 4 迁移后 {@code spring.factories} 里<b>不再有</b>
 * {@code org.springframework.boot.autoconfigure.EnableAutoConfiguration=} 这个键，
 * 老教程「往 spring.factories 追加自动配置类」的写法在 Boot 4 无效——
 * 本接口把这条做成布尔字段 {@code factoriesCarriesEnableAutoConfiguration}，可当场验证；
 * ④ 打成可执行 jar 后本模块资源在 {@code BOOT-INF/classes/} 里，
 * {@code locatedIn} 显示的是 jar 名而不是 {@code target/classes}，别以为读错了文件。</p>
 */
@Service
public class SpiFileService {

    /** 自动配置候选清单（Boot 4 的唯一入口） */
    private static final String AUTO_CONFIG_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** 老 SPI 文件（RunListener / FailureAnalyzer / ApplicationContextInitializer 等仍在用） */
    private static final String SPRING_FACTORIES = "META-INF/spring.factories";

    /**
     * 一份 {@code .imports} 资源的统计。
     *
     * @param locatedIn 资源所在容器（jar 文件名或模块 classes 目录），用于回答「谁提供了自动配置」
     * @param entryCount 有效条目数（已去掉注释与空行）
     * @param samples 前两条原文，证明文件确实被读到
     */
    public record ResourceEntry(String locatedIn, int entryCount, List<String> samples) {
    }

    /**
     * 一份 {@code spring.factories} 的统计。
     *
     * @param keyCount SPI 接口（键）个数
     * @param keys 键的简单名，例如 {@code SpringApplicationRunListener}
     * @param ours 是否本模块自己那份
     */
    public record FactoriesEntry(String locatedIn, int keyCount, List<String> keys, boolean ours) {
    }

    /**
     * 完整清单。
     *
     * @param candidateTotal 选择器实际能看到的候选自动配置类数（跨 jar 合并去重后）
     * @param ourFactories 本模块 spring.factories 的键 → 实现类，逐条列出
     * @param factoriesCarriesEnableAutoConfiguration 老键是否仍出现在 factories 里（实测应为 false）
     */
    public record Inventory(int candidateTotal, List<ResourceEntry> autoConfigImports,
            List<FactoriesEntry> factoriesFiles, Map<String, List<String>> ourFactories,
            boolean factoriesCarriesEnableAutoConfiguration, String conclusion) {
    }

    /** 生成清单；所有数字来自本次运行时的类路径，而不是写死的配置常量 */
    public Inventory inventory() {
        ClassLoader loader = getClass().getClassLoader();
        List<ResourceEntry> imports = new ArrayList<>();
        for (URL url : resources(loader, AUTO_CONFIG_IMPORTS)) {
            List<String> entries = readEntries(url);
            imports.add(new ResourceEntry(containerOf(url), entries.size(), entries.stream().limit(2).toList()));
        }
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, loader).getCandidates();

        List<FactoriesEntry> factories = new ArrayList<>();
        Map<String, List<String>> ours = new LinkedHashMap<>();
        boolean carriesEnableAutoConfiguration = false;
        for (URL url : resources(loader, SPRING_FACTORIES)) {
            Map<String, List<String>> parsed = parseFactories(readText(url));
            // 键是 FQN（org.springframework.boot.SpringApplicationRunListener），
            // 展示取简单名，读者才不会被一长串包名淹没
            List<String> simpleKeys = parsed.keySet().stream().map(this::simpleName).sorted().toList();
            // 判断「是不是本模块那份」只能看容器名：Boot 4 可执行 jar 里的资源 URL 是
            // jar:nested:/.../demo-springboot-startup-...jar!/... ，每一串都含模块名，
            // 直接用 url.toString().contains(...) 会把所有 jar 都认成自己（实测踩过）
            boolean isOurs = containerOf(url).startsWith("demo-springboot-startup");
            if (isOurs) {
                ours.putAll(parsed);
            }
            carriesEnableAutoConfiguration |= parsed.keySet().stream()
                    .anyMatch(key -> key.equals("org.springframework.boot.autoconfigure.EnableAutoConfiguration"));
            factories.add(new FactoriesEntry(containerOf(url), parsed.size(), simpleKeys, isOurs));
        }
        return new Inventory(candidates.size(), imports, factories, ours, carriesEnableAutoConfiguration,
                "候选自动配置类 " + candidates.size() + " 个，来自 " + imports.size()
                        + " 份 .imports（ImportCandidates 合并去重）；spring.factories 共 "
                        + factories.size() + " 份，其中本模块那份注册了 " + ourCount(ours)
                        + " 个实现类；老键 EnableAutoConfiguration 是否仍在 factories 里="
                        + carriesEnableAutoConfiguration);
    }

    private int ourCount(Map<String, List<String>> ours) {
        return ours.values().stream().mapToInt(List::size).sum();
    }

    private List<URL> resources(ClassLoader loader, String path) {
        try {
            // getResources 返回 Enumeration（比应用早年的 API 形状），转成 List 才能用流式处理
            return Collections.list(loader.getResources(path));
        } catch (IOException ex) {
            // getResources 只在底层目录不可读时抛（例如 target/classes 被删）。
            // 降级成空列表，接口仍返回「本模块之外」的清单，而不是整个 500
            return List.of();
        }
    }

    private String readText(URL url) {
        // 直接用 URL 开流：jar 内资源与目录资源都能读，
        // 不必把 URL 反解成 getResourceAsStream 的相对路径（那种写法在 exploded 目录下会错）
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    /** 读 .imports：忽略 {@code #} 注释与空行，其余每行一个类名 */
    private List<String> readEntries(URL url) {
        return readText(url).lines()
                .map(String::trim)
                // "# ent" 这类分隔注释在框架自己的文件里很常见，不算条目
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    /**
     * 解析 spring.factories。
     *
     * <p>续行以行尾 {@code \} 结束，必须先把逻辑行拼回来，
     * 否则同一键下的第二个实现类会被当成一个新键（本模块那份正是续行写法）。</p>
     */
    private Map<String, List<String>> parseFactories(String text) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        StringBuilder logical = new StringBuilder();
        for (String raw : text.lines().toList()) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.endsWith("\\")) {
                logical.append(line, 0, line.length() - 1);
                continue;
            }
            logical.append(line);
            String entry = logical.toString();
            logical.setLength(0);
            int separator = entry.indexOf('=');
            if (separator < 0) {
                // 没有等号的行不是键值对（例如某些文件里的裸续行残留），跳过而不是抛：
                // 一个格式怪行不该让整张清单消失
                continue;
            }
            String key = entry.substring(0, separator).trim();
            List<String> values = new ArrayList<>();
            for (String value : entry.substring(separator + 1).split(",")) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
            result.put(key, values);
        }
        return result;
    }

    /**
     * 从资源 URL 里取出「容器」名。
     *
     * <p>同一份资源在三种形态下长得不一样，这里都要能认出（全部实测过）：
     * ① exploded 目录 {@code file:/.../demo-springboot-startup/target/classes/META-INF/...}；
     * ② Boot 4 可执行 jar 内本模块资源
     * {@code jar:nested:/.../app.jar/!BOOT-INF/classes/!/META-INF/...}；
     * ③ 嵌套依赖 jar {@code jar:nested:/.../app.jar/!BOOT-INF/lib/spring-boot-autoconfigure-4.1.2.jar!/META-INF/...}。
     * 回显整串会让读者找不到重点，这里只留「哪个 jar / 哪个模块目录」。</p>
     */
    private String containerOf(URL url) {
        String text = url.toString();
        // 情形 ②：外层 jar 自己的 BOOT-INF/classes，容器名必须带上外层 jar，
        // 否则所有条目都显示成 "classes"，等于没回答「谁提供了自动配置」
        if (text.contains("!BOOT-INF/classes")) {
            return outerJar(text) + "/BOOT-INF/classes";
        }
        int bang = text.indexOf("!/");
        if (bang > 0) {
            String head = text.substring(0, bang);
            return head.substring(head.lastIndexOf('/') + 1);
        }
        int target = text.indexOf("/target/");
        if (target > 0) {
            // 情形 ①：exploded 目录（IDEA 或 spring-boot:run）：取模块名 + target/classes
            int moduleStart = text.lastIndexOf('/', target - 1) + 1;
            return text.substring(moduleStart, target) + "/target/classes";
        }
        return text;
    }

    /** 取 {@code jar:nested:} 形式里最外层那个 jar 的文件名（嵌套资源 URL 一定带着它） */
    private String outerJar(String text) {
        int bang = text.indexOf('!');
        String prefix = bang > 0 ? text.substring(0, bang) : text;
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        int slash = prefix.lastIndexOf('/');
        return slash >= 0 ? prefix.substring(slash + 1) : prefix;
    }

    private String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
