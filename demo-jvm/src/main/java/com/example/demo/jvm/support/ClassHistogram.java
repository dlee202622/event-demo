package com.example.demo.jvm.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 {@code jcmd <pid> GC.class_histogram} 的文本输出。
 *
 * <p>为什么需要解析：接口直接把几十万字节的原文返回给 curl 没有任何可读性，
 * 而排查内存问题时真正需要的只有两件事——排名前几的类和它们的实例数。
 * 这里保留原始格式解析，是为了让 HTTP 结果和读者自己在终端里跑 jcmd 看到的内容一致。</p>
 *
 * <p>输入格式（JDK 21 实测）：
 * <pre>
 *  num     #instances         #bytes  class name (module)
 * -------------------------------------------------
 *    1:         24053        1185120  [B (java.base@21.0.13)
 * Total:         32789        2815432
 * </pre></p>
 *
 * <p>坑：数组类名带方括号（{@code [B} 是 byte[]、{@code [Ljava.lang.Object;} 是 Object[]），
 * 且后面可能紧跟模块名，不能按「空白分隔取第三列」处理。</p>
 */
public final class ClassHistogram {

    /** 一行数据：排名、实例数、字节数、类名（含模块后缀）。 */
    public record Entry(int rank, long instances, long bytes, String className) {

        /** 单个实例的平均大小，用来说明「是数量多还是每个都大」。 */
        public double avgBytes() {
            return instances == 0 ? 0 : Math.round((double) bytes / instances);
        }
    }

    private static final Pattern ROW = Pattern.compile("^\\s*(\\d+):\\s+(\\d+)\\s+(\\d+)\\s+(.+)$");

    private ClassHistogram() {
    }

    /**
     * @param text  jcmd 原始输出
     * @param limit 只取前 N 行（按字节数倒序，jcmd 本身就是倒序输出）
     * @return 解析出的条目；表头和汇总行会被跳过
     */
    public static List<Entry> parse(String text, int limit) {
        List<Entry> entries = new ArrayList<>();
        for (String line : text.lines().toList()) {
            Matcher matcher = ROW.matcher(line);
            if (!matcher.matches()) {
                // 表头、分隔线、Total 行都不符合「数字: 数字 数字 名字」的形态
                continue;
            }
            entries.add(new Entry(
                    Integer.parseInt(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3)),
                    matcher.group(4).trim()));
            if (entries.size() >= limit) {
                // jcmd 可能列出几十万个类，读到 limit 就停，避免整段文本进内存
                break;
            }
        }
        return entries;
    }

    /**
     * 汇总行里的总字节数。
     *
     * <p>实测（JDK 21，走 MBean 与走 jcmd 输出一致）汇总行是
     * {@code Total        441645       20433424}，<strong>没有冒号</strong>，
     * 而表头行 {@code num #instances #bytes class name} 也不以 Total 开头，
     * 所以这里只按前缀 Total 匹配，再取第二列之后的第一个数字（实例数）和第二个数字（字节数）。</p>
     */
    public static long totalBytes(String text) {
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("Total")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            // 第二列是实例数、第三列是字节数；个别发行版把首列写成 "Total:"，不影响这两列
            if (parts.length >= 3 && parts[1].matches("\\d+")) {
                return Long.parseLong(parts[2]);
            }
        }
        return 0;
    }
}
