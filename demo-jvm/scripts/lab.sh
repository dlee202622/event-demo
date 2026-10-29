#!/usr/bin/env bash
#
# demo-jvm 实验分发器。
#
# 为什么需要它：本模块每个案例的 JVM 参数都不一样——栈深度要 -Xss、
# 元空间要 -XX:MaxMetaspaceSize、大对象晋升要小堆才看得出现象。
# 这些参数有一个共同点：只能在进程启动前给，代码里改不了。
# 于是「复制文档里那行命令」成了唯一正确的姿势，而手写这行命令恰恰最容易漏参数。
#
# 脚本做三件事：把旗标配对 → 把真实命令原样打印一次 → 执行并透传退出码。
# 打印这一步是重点：文档里出现的每一行命令都必须和实际执行的那行完全一致，
# 读者照着敲才能得到同样的输出；只在脚本里藏着的参数是复现不了的东西。
#
# 用法：
#   demo-jvm/scripts/lab.sh <案例> [附加参数...]
#   demo-jvm/scripts/lab.sh                 # 不带参数列出全部案例
#
# 约定：
#   * 附加参数原样透传给 Java，例如 lab.sh leak --cases=intern --items=400；
#   * 需要 JDK 而不是 JRE：实验要用 jcmd / javap / jstat；
#   * 退出码就是 Java 进程的退出码——OOM 类案例预期非 0，别把它当脚本坏了。
#
# 兼容说明：macOS 自带的 bash 停在 3.2.57，所以这里不用 4.x 才有的写法
# （关联数组、${var,,} 小写转换都禁用），也刻意不开 -u：
# 案例数组经常是空的（例如 stack-depth 没有旗标），3.2 在 set -u 下会把
# "${FLAGS[@]}" 的空展开当成「未定义变量」直接退出。
set -eo pipefail

# 仓库根目录 = 本脚本所在目录的上两级（demo-jvm/scripts/ -> 仓库根）
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLASSES="$ROOT/demo-jvm/target/classes"
MAIN=com.example.demo.jvm

# 找一个「带工具的 JDK」而不是 PATH 上的 java：
#   1) JAVA_HOME 优先，它和 ./mvnw 用的是同一个，编译产物与运行时的版本才一致；
#   2) macOS 上 /usr/bin/java 是 Apple 的 shim，readlink -f 解析不出真身，
#      必须问 /usr/libexec/java_home；
#   3) Linux 上 java 通常是 alternatives 软链，readlink -f 能解析。
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JHOME="$JAVA_HOME"
elif [[ -x /usr/libexec/java_home ]] && JHOME="$(/usr/libexec/java_home 2>/dev/null)"; then
  :
else
  JHOME="$(cd "$(dirname "$(readlink -f "$(command -v java)")")/.." && pwd)"
fi
JAVA="$JHOME/bin/java"

if [[ ! -x "$JAVA" ]]; then
  echo "!! 没找到 JDK（推出来的 home=$JHOME）。请 export JAVA_HOME 指向一个 JDK 再跑。" >&2
  exit 1
fi

usage() {
  cat <<'EOF'
案例清单（左边是脚本自动附带的 JVM 旗标，右边是案例名）：

  （无）                 stack-depth      栈大小 → 递归深度（场景 2）
  （无）                 local-slots      javap 看局部变量槽位复用（场景 2）
  -XX:NativeMemoryTracking=summary        observe        Web 观测面（场景 1）
                         换端口：SERVER_PORT=9015 demo-jvm/scripts/lab.sh observe
  -Xms64m -Xmx64m        oom-heap         堆溢出（场景 3）
  -Xss256k               oom-stack        栈溢出（场景 3）
  -XX:MaxMetaspaceSize=48m            oom-metaspace  元空间溢出（场景 3）
  -Xmx64m -XX:MaxDirectMemorySize=48m oom-direct     直接内存溢出（场景 3）
  -Xmx64m                oom-thread       线程数上限（场景 3，反例：它通常不 OOM）
  -Xmx64m -XX:+UseSerialGC           oom-gcoverhead  GC 时间上限（场景 3，实测阴性）
  -Xms128m -Xmx128m + 收集器旗标   gc <collector>    单收集器跑确定性负载（场景 4）
                                      collector = serial | parallel | g1 | zgc | zgc-gen
  （旗标由脚本按变体逐个给）  gc-compare     五个收集器横向对比（场景 4）
  （旗标由脚本按变体逐个给）  age            对象年龄 / 晋升 / 大对象（场景 5）
  （旗标由脚本按变体逐个给）  leak           泄漏版 vs 修好版，进程外用 jcmd 取证（场景 6）
  -Xms256m -Xmx256m      leak-child       只起一个「听 stdin 指令」的泄漏进程，手动练 jcmd

所有案例都要求先编译：./mvnw -q -pl demo-jvm compile
EOF
}

if [[ ! -d "$CLASSES" ]]; then
  echo "!! 找不到 $CLASSES，先跑：./mvnw -q -pl demo-jvm compile" >&2
  exit 1
fi

CASE="${1:-}"
if [[ -z "$CASE" ]]; then
  usage
  exit 0
fi
shift

# 剩下的都当成 Java 的 --key=value 参数透传
ARGS=()
for extra in "$@"; do
  ARGS+=("$extra")
done

FLAGS=()
CLASS=""

case "$CASE" in
  observe)
    # Web 观测面必须有 NMT 才有 native-memory 数据，所以直接 java -jar，
    # 不用 spring-boot:run——后者起的那个 JVM 只是构建器，旗标不会落到应用进程上。
    JAR=""
    for candidate in "$ROOT"/demo-jvm/target/demo-jvm-*.jar; do
      [[ -f "$candidate" ]] && { JAR="$candidate"; break; }
    done
    if [[ -z "$JAR" ]]; then
      echo "!! 还没有打包，先跑：./mvnw -q -pl demo-jvm -am package -DskipTests" >&2
      exit 1
    fi
    ;;

  stack-depth)  CLASS="$MAIN.lab.StackDepthLab" ;;
  local-slots)  CLASS="$MAIN.lab.LocalSlotLab" ;;

  oom-heap)       FLAGS=(-Xms64m -Xmx64m);                        CLASS="$MAIN.oom.OomLab"; ARGS+=(--case=heap) ;;
  oom-stack)      FLAGS=(-Xss256k);                               CLASS="$MAIN.oom.OomLab"; ARGS+=(--case=stack) ;;
  oom-metaspace)  FLAGS=(-XX:MaxMetaspaceSize=48m);               CLASS="$MAIN.oom.OomLab"; ARGS+=(--case=metaspace) ;;
  oom-direct)     FLAGS=(-Xmx64m -XX:MaxDirectMemorySize=48m);    CLASS="$MAIN.oom.OomLab"; ARGS+=(--case=direct) ;;
  oom-thread)     FLAGS=(-Xmx64m);                                CLASS="$MAIN.oom.OomLab"; ARGS+=(--case=thread) ;;
  oom-gcoverhead) FLAGS=(-Xmx64m -XX:+UseSerialGC);               CLASS="$MAIN.oom.OomLab"; ARGS+=(--case=gcOverhead) ;;

  gc)
    # 第一个附加参数是收集器名，取走后剩下的继续透传给 GcLab
    COLLECTOR="serial"
    [[ ${#ARGS[@]} -gt 0 ]] && COLLECTOR="${ARGS[0]}"
    ARGS=("${ARGS[@]:1}")
    # 堆必须钉死成 -Xms128m -Xmx128m：不给的话 HotSpot 按物理内存的 1/4 定 -Xmx，
    # 16 GB 的笔记本上是 4 GB、服务器上可能是几十 GB，648 MB 的负载一路逛完都触发不了几次回收，
    # GC 次数和停顿就变成「看你机器多大」而不是「看你用哪个收集器」。
    # Xms=Xmx 是为了去掉堆自动伸缩这个变量，让五个收集器的起点完全一样。
    case "$COLLECTOR" in
      serial)   FLAGS=(-Xms128m -Xmx128m -XX:+UseSerialGC) ;;
      parallel) FLAGS=(-Xms128m -Xmx128m -XX:+UseParallelGC) ;;
      g1)       FLAGS=(-Xms128m -Xmx128m -XX:+UseG1GC) ;;
      zgc)      FLAGS=(-Xms128m -Xmx128m -XX:+UseZGC) ;;
      # 分代 ZGC 在 JDK 21 是实验特性：少一条 -XX:+UnlockExperimentalVMOptions
      # 就是启动失败，而不是「退化成非分代」，所以三条必须一起给
      zgc-gen)  FLAGS=(-Xms128m -Xmx128m -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:+ZGenerational) ;;
      *)
        echo "!! 未知收集器：$COLLECTOR（可选 serial / parallel / g1 / zgc / zgc-gen）" >&2
        exit 1
        ;;
    esac
    CLASS="$MAIN.gc.GcLab"
    # 报告里的 collector 字段要和真实旗标同源，否则读者会拿名字去比对而结论错
    ARGS+=(--collector="$COLLECTOR")
    ;;

  gc-compare) CLASS="$MAIN.gc.GcCompareLab" ;;
  age)        CLASS="$MAIN.gc.AgePromotionLab" ;;
  leak)       CLASS="$MAIN.leak.LeakDetectLab" ;;

  leak-child)
    FLAGS=(-Xms256m -Xmx256m)
    CLASS="$MAIN.leak.LeakLab"
    echo "提示：这个进程停在阶段 1，等你在 stdin 输入 GO / EXIT。" >&2
    echo "     另开一个终端，用第一行报告里的 pid 敲：jcmd <pid> GC.class_histogram" >&2
    ;;

  *)
    echo "!! 未知案例：$CASE" >&2
    echo >&2
    usage >&2
    exit 1
    ;;
esac

# 统一打印并执行。%q 会把带空格的参数印成可复制的形式，
# 免得读者以为 --note=hello world 是两个参数。
# 提示符单独打印：printf 的格式串是「每个参数循环一次」，
# 把 '$ ' 混在格式里会印成一串 "$ java $ -cp $ ..."。
if [[ "$CASE" == observe ]]; then
  printf '$ '
  printf '%q ' "$JAVA" -XX:NativeMemoryTracking=summary -jar "$JAR" "${ARGS[@]}"
  printf '\n'
  exec "$JAVA" -XX:NativeMemoryTracking=summary -jar "$JAR" "${ARGS[@]}"
fi

printf '$ '
printf '%q ' "$JAVA" "${FLAGS[@]}" -cp "$CLASSES" "$CLASS" "${ARGS[@]}"
printf '\n'
exec "$JAVA" "${FLAGS[@]}" -cp "$CLASSES" "$CLASS" "${ARGS[@]}"
