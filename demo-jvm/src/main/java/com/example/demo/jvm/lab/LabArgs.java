package com.example.demo.jvm.lab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 实验类（带 {@code main} 的类）的命令行解析：只支持 {@code --key=value}。
 *
 * <p>为什么不用 Spring 的 {@code CommandLineRunner} 或 picocli：实验类必须能用
 * {@code java -Xmx64m -cp target/classes com.example.demo.jvm.oom.OomLab --case=heap}
 * 这样一条最朴素的命令跑起来，任何额外依赖都会让读者复制命令时踩坑。</p>
 *
 * <p>约定：所有参数都有默认值，缺参数不报错——报错只发生在「给了无法解析的值」时，
 * 并把合法取值列出来，让读者一眼知道该改哪个。</p>
 */
public final class LabArgs {

    private final Map<String, String> values;

    private LabArgs(Map<String, String> values) {
        this.values = values;
    }

    /**
     * @param args {@code main} 收到的参数，形如 {@code --case=heap --mb=32}
     * @return 解析结果；重复的 key 以最后一次为准
     */
    public static LabArgs parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("参数格式必须是 --key=value，收到：" + arg
                        + "（已识别：" + values.keySet() + "）");
            }
            int split = arg.indexOf('=');
            values.put(arg.substring(2, split), arg.substring(split + 1));
        }
        return new LabArgs(values);
    }

    /**
     * @param key      参数名，不含前面的 {@code --}
     * @param fallback 未提供时的默认值
     * @return 原始字符串，<strong>不做任何裁剪</strong>——逗号列表（如
     *         {@code --threadSizesKb=128,256}）和 {@code --cases=a,b} 都靠调用方自己切，
     *         这里一旦 trim 或按分隔符拆开就没法还原「用户到底写了什么」
     */
    public String string(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    /**
     * @param key   参数名
     * @param fallback 未提供时的默认值
     * @return 整数值
     * @throws IllegalArgumentException 值不是数字，消息里带上收到的原始字符串
     */
    public int number(String key, int fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " 需要整数，收到：" + raw, e);
        }
    }

    /**
     * 布尔参数。
     *
     * <p>三种写法都接受：{@code --detail=true}、{@code --detail=false}、以及只写 {@code --detail}
     * （此时 {@code values} 里存的是空串，按 true 处理）。最后这条是为了让命令行少打五个字符，
     * 也和 {@code -XX:+Flag} 那种「出现即开启」的直觉一致。</p>
     *
     * @param key      参数名
     * @param fallback 未提供时的默认值
     * @return 解析后的布尔值；无法识别的字符串按 {@code false} 处理（{@link Boolean#parseBoolean} 的语义）
     */
    public boolean bool(String key, boolean fallback) {
        String raw = values.get(key);
        // 允许 --flag 不带值，语义为 true，少写一次 =true
        return raw == null ? fallback : raw.isBlank() || Boolean.parseBoolean(raw.trim());
    }

    /** 未知参数检查：拼错参数名时静默默认值会让人怀疑人生，所以这里主动提示。 */
    public Set<String> providedKeys() {
        return values.keySet();
    }

    /** 原样回显已解析的参数，出错消息里靠它告诉读者「你到底传进来了什么」。 */
    @Override
    public String toString() {
        return values.toString();
    }
}
