package com.example.demo.jvm.lab;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 场景2 的第二半：读字节码，把「局部变量槽」从比喻变成可核对的数字。
 *
 * <p>为什么需要它：虚拟机栈这一区既不在堆里也不在 Metaspace 里，
 * {@code MemoryPoolMXBean} 和 {@code jcmd} 都量不到它，唯一的观测手段是
 * {@code javap -v} 读方法 Code 属性里的 {@code max_stack} / {@code max_locals}。
 * 而 {@link StackDepthLab} 量到的「每帧约 24 字节」正是这些槽加上操作数栈和返回地址的结果，
 * 两个实验合起来才是完整的虚拟机栈。</p>
 *
 * <p>实现方式：跑 {@code javap -p -v <class 文件>}，解析每个方法的描述符、{@code ACC_STATIC}、
 * {@code stack=/locals=/args_size=} 和 {@code LocalVariableTable}，
 * 再按 JVM 规范<strong>用描述符手算参数应占的槽数</strong>，与 {@code max_locals} 对比。
 * 差值就是方法体里自己声明的局部变量占用——这一步是本实验的落点：
 * 读者能亲手验证「{@code long}/{@code double} 占两槽」「实例方法槽 0 是 {@code this}」
 * 「顺序块复用槽」这三条规则。</p>
 *
 * <p>坑：
 * <ul>
 *   <li>{@code javap} 在 JDK 的 {@code bin} 目录里，不一定在 PATH 上，所以用
 *       {@code java.home} 拼绝对路径；</li>
 *   <li>{@code -c} 只给字节码，不给 {@code stack=/locals=}，必须用 {@code -v}；</li>
 *   <li>{@code max_locals} 是编译器的槽位复用结果的峰值，
 *       比「变量个数」小是完全正常的，别当成 bug；</li>
 *   <li>class 文件必须已经编译好（{@code target/classes}），
 *       所以本实验只能在 {@code ./mvnw compile} 之后跑。</li>
 * </ul></p>
 */
public final class LocalSlotLab {

    /** 方法头，形如 {@code   public static long intThenLong(int a, long b);}。 */
    private static final Pattern METHOD_HEADER = Pattern.compile("^ {2}.*\\(.*\\);$");
    private static final Pattern DESCRIPTOR = Pattern.compile("^\\s+descriptor: (\\S+)\\s*$");
    private static final Pattern FLAGS = Pattern.compile("^\\s+flags: \\(0x[0-9a-fA-F]+\\) (.*)$");
    private static final Pattern CODE_SIZE = Pattern.compile("stack=(\\d+), locals=(\\d+), args_size=(\\d+)");
    /** LocalVariableTable 的一行：{@code Start Length Slot Name Signature}。 */
    private static final Pattern LOCAL_ROW = Pattern.compile("^\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)\\s+(.*)$");

    /**
     * 对 {@link LocalSlotSample} 跑一次 {@code javap -p -v -l}，把每个方法的槽位账算出来。
     *
     * <p>不接受参数：样本类是编译期写死的，换样本等于换结论，所以要看别的方法就自己改
     * {@code LocalSlotSample} 再 {@code ./mvnw -pl demo-jvm compile}。
     * 报告里第一行是<strong>真正执行的 javap 命令</strong>，复制它就能复现全部输出。</p>
     */
    public static void main(String[] args) {
        String classFile = classFileOf(LocalSlotSample.class);
        // -p 连私有成员一起看，-v 才有 stack=/locals=，-l 才有 LocalVariableTable（少了 -l 就看不到槽号复用）
        List<String> command = List.of(ChildJvm.jdkTool("javap"), "-p", "-v", "-l", classFile);
        ChildJvm.Result result = ChildJvm.runCommand(command, 30_000);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("javap 执行失败（退出码 " + result.exitCode() + "）："
                    + result.stderr().strip());
        }

        LabReport report = LabReport.of("local-slots")
                .put("target", LocalSlotSample.class.getName())
                // 报告里回显真正执行过的命令，而不是「我觉得我执行的命令」——
                // 读者复制这行必须能复现出同样的输出
                .put("javap", String.join(" ", command));
        int[] methods = parseAndReport(result.stdout(), report);
        report.put("methods", methods[0]);
        // 有一行 mismatch 就说明解析器或规则理解错了，这是本实验的自检
        report.put("descriptorMismatch", methods[1]);
        report.print();

        System.out.println();
        System.out.println("字段说明");
        System.out.println("  argSlots  按描述符算出的参数槽数：long/double=2，其他（含引用、数组）=1");
        System.out.println("  thisSlot  实例方法为 1（槽 0 存 this），静态方法为 0");
        System.out.println("  locals    javap 的 max_locals，也就是这一帧局部变量表的大小");
        System.out.println("  bodySlots locals - argSlots - thisSlot = 方法体自己占的槽");
        System.out.println("  vars      LocalVariableTable：变量名@槽号[起始字节码+有效长度]，同槽号但区间不重叠就是复用");
        System.out.println();
        System.out.println("读法：bodySlots 小于「方法里声明的变量个数」，说明编译器把槽复用了；");
        System.out.println("      vars 里 long 参数只出现一次却把下一个槽号挤掉，说明它占两槽。");
    }

    /**
     * 解析 javap 输出。
     *
     * @return {@code [方法数, 描述符推算与实际不符的方法数]}
     */
    private static int[] parseAndReport(String javapOutput, LabReport report) {
        String method = null;
        String descriptor = null;
        boolean isStatic = false;
        boolean inLocalTable = false;
        StringBuilder slots = new StringBuilder();
        int stack = 0;
        int locals = 0;
        int count = 0;
        int mismatch = 0;

        for (String line : javapOutput.lines().toList()) {
            if (METHOD_HEADER.matcher(line).find()) {
                if (method != null) {
                    // 遇到下一个方法头，才把上一块的结果写出去：保证每个方法只产出一行
                    mismatch += flush(report, method, descriptor, isStatic, stack, locals, slots) ? 1 : 0;
                    count++;
                }
                method = methodName(line);
                descriptor = null;
                isStatic = false;
                inLocalTable = false;
                slots.setLength(0);
                stack = 0;
                locals = 0;
                continue;
            }
            if (method == null) {
                // 类头、常量池这些还没进到方法里
                continue;
            }
            Matcher desc = DESCRIPTOR.matcher(line);
            if (desc.matches()) {
                descriptor = desc.group(1);
                continue;
            }
            Matcher flag = FLAGS.matcher(line);
            if (flag.matches()) {
                isStatic = flag.group(1).contains("ACC_STATIC");
            }
            // 每个方法只有一个 Code 属性；抽象方法与 native 方法没有，会保持 locals=0
            Matcher code = CODE_SIZE.matcher(line);
            if (code.find()) {
                stack = Integer.parseInt(code.group(1));
                locals = Integer.parseInt(code.group(2));
            }
            if (line.strip().equals("LocalVariableTable:")) {
                inLocalTable = true;
                continue;
            }
            if (inLocalTable) {
                Matcher row = LOCAL_ROW.matcher(line);
                if (row.matches()) {
                    if (slots.length() > 0) {
                        slots.append(',');
                    }
                    // 名字@槽号[起始字节码位置+有效长度]：区间不重叠的两个变量能共用一个槽，
                    // 这正是 javac 把 max_locals 压小的原因，只报槽号说不清楚
                    slots.append(row.group(4))
                            .append('@').append(row.group(3))
                            .append('[').append(row.group(1)).append('+').append(row.group(2)).append(']');
                } else if (line.strip().startsWith("Start")) {
                    // 表头那一行「Start Length Slot Name Signature」不是数据，不能让下面那句把状态清掉
                } else if (!line.strip().isEmpty()) {
                    // 表结束：后面可能是 MethodParameters / StackMapTable，不再按槽位解析
                    inLocalTable = false;
                }
            }
        }
        if (method != null) {
            // 最后一个方法没有「下一个方法头」来触发，循环外补一次
            mismatch += flush(report, method, descriptor, isStatic, stack, locals, slots) ? 1 : 0;
            count++;
        }
        return new int[]{count, mismatch};
    }

    /**
     * 把一个方法的结论写成报告的一行，并顺手校验「描述符算出的参数槽 + this ≤ max_locals」。
     *
     * <p>不等式方向：{@code max_locals} 一定不小于参数与 this 之和，
     * 如果算出来超过了，说明解析串了行或规则理解错，返回 true 让调用方计数并暴露出来。</p>
     *
     * @return 该方法是否出现 {@code bodySlots < 0} 的矛盾
     */
    private static boolean flush(LabReport report, String method, String descriptor, boolean isStatic,
                                 int stack, int locals, StringBuilder slots) {
        int argSlots = descriptor == null ? 0 : argSlots(descriptor);
        int thisSlot = isStatic ? 0 : 1;
        int bodySlots = locals - argSlots - thisSlot;
        // 没有 Code 属性（抽象/native）时 locals=0，不算矛盾
        boolean inconsistent = locals > 0 && bodySlots < 0;
        report.put("method." + method,
                (isStatic ? "static" : "instance")
                        + " desc=" + descriptor
                        + " argSlots=" + argSlots
                        + " thisSlot=" + thisSlot
                        + " stack=" + stack
                        + " locals=" + locals
                        + " bodySlots=" + bodySlots
                        + (inconsistent ? " MISMATCH" : "")
                        + " vars=" + (slots.length() == 0 ? "-" : slots));
        return inconsistent;
    }

    /** 从方法头里取出可当键用的名字：{@code intThenLong(int, long)}。 */
    private static String methodName(String header) {
        String trimmed = header.strip();
        int paren = trimmed.indexOf('(');
        int space = trimmed.lastIndexOf(' ', paren);
        // 去掉修饰符，只留「名字(参数类型)」，避免同名不同参的方法撞键
        String name = trimmed.substring(space + 1, trimmed.length() - 1);
        return name.replace(" ", "");
    }

    /**
     * 按 JVM 规范算描述符的入参槽数。
     *
     * @param descriptor 形如 {@code (IJ)V} 的方法描述符
     * @return 参数占用的局部变量槽数量
     */
    static int argSlots(String descriptor) {
        int open = descriptor.indexOf('(');
        int close = descriptor.indexOf(')', open);
        String params = descriptor.substring(open + 1, close);
        int slots = 0;
        int index = 0;
        while (index < params.length()) {
            char current = params.charAt(index);
            // [ 只是数组维度，不额外占槽：int[][] 和 int 一样是一个引用槽
            while (current == '[') {
                index++;
                current = params.charAt(index);
            }
            if (current == 'J' || current == 'D') {
                slots += 2;
                index++;
            } else if (current == 'L') {
                slots += 1;
                // 对象类型一直到分号结束，例如 Ljava/lang/String;
                index = params.indexOf(';', index) + 1;
            } else {
                slots += 1;
                index++;
            }
        }
        return slots;
    }

    /**
     * 定位一个类已经编译好的 class 文件绝对路径。
     *
     * <p>用类加载器的资源地址而不是拼 {@code target/classes}：前者在 Maven、IDEA、
     * 测试和 {@code java -cp} 四种启动方式下都对，硬拼目录只在其中一种下对。</p>
     */
    private static String classFileOf(Class<?> target) {
        String resource = target.getName().replace('.', '/') + ".class";
        try {
            return Path.of(target.getClassLoader().getResource(resource).toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("无法定位 " + resource + " 的 class 文件：" + e.getMessage(), e);
        }
    }

    private LocalSlotLab() {
    }
}
