# demo-springboot-startup

把「Spring Boot 启动原理」拆成六个**能跑、能看、能复现**的场景。不依赖 MySQL / Redis / MQ，
只用一个进程 + 十几个只读接口，把「注解 → 类 → 容器里多了什么」和启动链路内部状态全部摊开。

**学习顺序建议从场景 0 开始**：注解本身不做任何事，处理注解的类才做事；先把这张对照表立起来，
再看时间线（场景 1~2）、条件（场景 3）、实例化（场景 4）与失败（场景 5）才有落点。

| # | 场景 | 怎么触发 | 观察到什么 |
| --- | --- | --- | --- |
| 0 | 注解与类（起点） | `GET /api/startup/annotations`、`/annotations/tree`、`/conditional`、`/spi`、`/provenance`、`/proxymethods`、`/feature` | 17 行「注解 → 干活类」对照表；`@SpringBootApplication` 的元注解树（12 次出现 / 9 个类型）；21 个条件注解落到 11 个 Condition 类；11 份 `.imports` 汇总出 44 个候选；191 个 Bean 定义的来源分类；`proxyBeanMethods` 两档实测差值；手写 `@EnableXxx` 全链路 |
| 1 | 启动事件时间线 | 启动日志 / `GET /api/startup/timeline` | 7 个 `SpringApplicationRunListener` 阶段 + 3 个容器事件的顺序、耗时、线程 |
| 2 | 启动模式对比 | `GET /api/startup/modes/compare`、`/modes/port-in-use` | NONE / SERVLET / REACTIVE / AUTO-DEDUCED 的容器类、Bean 定义数、WebServer 类型与端口；端口冲突的异常链 |
| 3 | 自动配置揭秘 | `GET /api/startup/autoconfig`、`/autoconfig/search`、`/autoconfig/list`、`/autoconfig/exclude-demo` | 条件评估报告的真实内容；改开关后 matched 数量变化；排除一个自动配置的真实后果 |
| 4 | Bean 生命周期 | `GET /api/startup/beanlife/trace`、`/beanlife/resident` | constructor → destroy-method 的 17 条打点记录与顺序，含 BPP 换代理现场 |
| 5 | 启动失败双结局 | `DEMO_FAIL_MODE=analyzer` 与 `DEMO_FAIL_MODE=raw` 各启动一次 | 友好报告（42 行、0 条堆栈帧）vs 满屏堆栈（63 行、30 条堆栈帧） |

> 场景 5 无法用接口触发：启动失败的应用根本没有接口。

## 1. 启动

```bash
# 无需任何中间件，直接起
./mvnw -pl demo-springboot-startup spring-boot:run

# 或打包后运行
./mvnw -pl demo-springboot-startup -am package -DskipTests
java -jar demo-springboot-startup/target/demo-springboot-startup-0.0.1-SNAPSHOT.jar
```

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `9006` | 应用端口（9001~9003 是 IDEA 常驻服务，9004=demo-redis、9005=demo-jvm） |
| `DEMO_PROPERTY_TOGGLE` | `false` | 场景 3：`@ConditionalOnProperty` 开关 |
| `DEMO_FEATURE_COLOR` | `orange` | 场景 3：自定义条件 `FeatureColorCondition` 的输入，含 `green` 才放行 |
| `DEMO_ANNOTATION_NAMES` | `alpha,beta,gamma` | 场景 0：宽松绑定把逗号串绑成 `List<String>` |
| `DEMO_ANNOTATION_RETRIES` | `2` | 场景 0：`full` 档属性值；子应用用命令行参数 `--…=7` 覆盖它 |
| `DEMO_ANNOTATION_LABEL` | `loose-binding-works` | 场景 0：验证 `feature-label` → `featureLabel` 的松散命名 |
| `DEMO_FAIL_MODE` | `none` | 场景 5：`analyzer` / `raw` 会在 `@PostConstruct` 里抛异常，用来对比两种失败输出 |

> 下文实测在临时端口 `9014` 上采集（`SERVER_PORT=9014 java -jar ...`），默认端口是 9006；
> 除端口号与毫秒级耗时外内容与默认启动一致。
> 环境：Spring Boot 4.1.2 / Java 21.0.13 / Tomcat 11.0.27，实测日期 2026-10-29。

## 2. 场景 0：从注解讲起

一组只读接口，把「你写的注解 → 框架里干活的类 → 容器里多了什么」全部数字化。
所有数字来自反射与类路径现场计算，换 Boot 版本时 `AnnotationMechanicsTest` 会先报错。

### 2.1 注解 → 类 对照表

```bash
curl -s http://localhost:9014/api/startup/annotations \
  | jq -r '"rowCount=\(.rowCount) present=\(.presentCount) missing=\(.missing)",
           (.rows[] | "\(.entry)  ->  \(.activatesClass)")'
```

返回（实测 17 行的「干活类」全部在当前类路径上，`missing=[]`）：

```
rowCount=17 present=17 missing=[]
@SpringBootConfiguration  ->  org.springframework.context.annotation.Configuration
@EnableAutoConfiguration  ->  org.springframework.boot.autoconfigure.AutoConfigurationImportSelector
@AutoConfiguration  ->  org.springframework.boot.autoconfigure.AutoConfigurationPackages
@ComponentScan  ->  org.springframework.context.annotation.ClassPathBeanDefinitionScanner
@ComponentScan.Filter(CUSTOM)  ->  org.springframework.boot.context.TypeExcludeFilter,org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
@Conditional  ->  org.springframework.boot.autoconfigure.condition.OnClassCondition
@Import  ->  org.springframework.context.annotation.ConfigurationClassParser
@Bean  ->  org.springframework.context.annotation.ConfigurationClassBeanDefinitionReader
@Configuration(proxyBeanMethods)  ->  org.springframework.context.annotation.ConfigurationClassEnhancer
@ConfigurationProperties  ->  org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
@EnableConfigurationProperties  ->  org.springframework.boot.context.properties.EnableConfigurationPropertiesRegistrar
@EnableScheduling（@EnableXxx 通例）  ->  org.springframework.scheduling.annotation.SchedulingConfiguration
@PostConstruct / @PreDestroy  ->  org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor
@Value  ->  org.springframework.context.support.PropertySourcesPlaceholderConfigurer
@RestController + @GetMapping  ->  org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
META-INF/spring.factories（不是注解）  ->  org.springframework.core.io.support.SpringFactoriesLoader
FailureAnalyzer（接口）  ->  org.springframework.boot.diagnostics.AbstractFailureAnalyzer
```

### 2.2 把 `@SpringBootApplication` 剖开

```bash
curl -s "http://localhost:9014/api/startup/annotations/tree?className=" \
  | jq -r '"treeRows=\(.treeRows) mergedOccurrences=\(.mergedOccurrences) mergedUniqueTypes=\(.mergedUniqueTypes)",
           "countByStrategy=\(.countByStrategy)",
           (.tree[] | "d\(.depth) \(.annotation)  <-  \(.declaredOn)")'
```

返回（实测；`d0` 是直接标在主类上的，`d1` 那三个就是「三合一」）：

```
treeRows=12 mergedOccurrences=12 mergedUniqueTypes=9
countByStrategy={"DIRECT":12,"INHERITED_ANNOTATIONS":12,"SUPERCLASS":12,"TYPE_HIERARCHY":12}
d0 org.springframework.boot.autoconfigure.SpringBootApplication  <-  com.example.demo.startup.StartupApplication
d1 org.springframework.boot.SpringBootConfiguration  <-  org.springframework.boot.autoconfigure.SpringBootApplication
d2 org.springframework.context.annotation.Configuration  <-  org.springframework.boot.SpringBootConfiguration
d3 org.springframework.stereotype.Component  <-  org.springframework.context.annotation.Configuration
d4 org.springframework.stereotype.Indexed  <-  org.springframework.stereotype.Component
d2 org.springframework.stereotype.Indexed  <-  org.springframework.boot.SpringBootConfiguration
d1 org.springframework.boot.autoconfigure.EnableAutoConfiguration  <-  org.springframework.boot.autoconfigure.SpringBootApplication
d2 org.springframework.boot.autoconfigure.AutoConfigurationPackage  <-  org.springframework.boot.autoconfigure.EnableAutoConfiguration
d3 org.springframework.context.annotation.Import  <-  org.springframework.boot.autoconfigure.AutoConfigurationPackage
d2 org.springframework.context.annotation.Import  <-  org.springframework.boot.autoconfigure.EnableAutoConfiguration
d1 org.springframework.context.annotation.ComponentScan  <-  org.springframework.boot.autoconfigure.SpringBootApplication
d0 org.springframework.context.annotation.ComponentScan  <-  com.example.demo.startup.StartupApplication
```

同一个接口换一个类名，就能解剖框架自己的配置类（注意 Boot 4 的包名已经拆到
`webmvc.autoconfigure`，用 Boot 3 的 `autoconfigure.web.servlet` 会得到 400 + 提示）：

```bash
curl -s "http://localhost:9014/api/startup/annotations/tree?className=org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration" \
  | jq -r '"treeRows=\(.treeRows) unique=\(.mergedUniqueTypes)",
           (.tree[] | "d\(.depth) \(.annotation|split(".")[-1])  <-  \(.declaredOn|split(".")[-1])")'
```

返回（实测）：

```
treeRows=14 unique=12
d0 AutoConfiguration  <-  WebMvcAutoConfiguration
d1 Configuration  <-  AutoConfiguration
d2 Component  <-  Configuration
d3 Indexed  <-  Component
d1 AutoConfigureBefore  <-  AutoConfiguration
d1 AutoConfigureAfter  <-  AutoConfiguration
d0 ConditionalOnWebApplication  <-  WebMvcAutoConfiguration
d1 Conditional  <-  ConditionalOnWebApplication
d0 ConditionalOnClass  <-  WebMvcAutoConfiguration
d1 Conditional  <-  ConditionalOnClass
d0 ConditionalOnMissingBean  <-  WebMvcAutoConfiguration
d1 Conditional  <-  ConditionalOnMissingBean
d0 AutoConfigureOrder  <-  WebMvcAutoConfiguration
d0 ImportRuntimeHints  <-  WebMvcAutoConfiguration
```

这就是「注解 → 类」链条在框架自己身上的样子：`@AutoConfiguration` 往下还是
`@Configuration → @Component`，三个 `@ConditionalOnXxx` 头上挂的是同一个根 `@Conditional`。

三个结论：**12 次出现 / 9 个唯一类型**（`ComponentScan`、`Import`、`Indexed` 各两次）；
四种 `SearchStrategy` 计数相同——Spring 7 只剩这 4 个值，它们只控制沿类继承链找多深，
**不控制元注解是否展开**；`@Filter` 的 `value` / `classes` 是 `@AliasFor`，
只有 Spring 的合成视图把两者填成同一个数组（返回值里的 `filterAliasProof` 做了并排对照）。

### 2.3 两个 SPI 文件决定候选名单

```bash
curl -s http://localhost:9014/api/startup/annotations/spi \
  | jq -r '"candidateTotal=\(.candidateTotal)  imports文件数=\(.autoConfigImports|length)  factories文件数=\(.factoriesFiles|length)",
           (.autoConfigImports[] | "\(.locatedIn)  \(.entryCount) 行"),
           "ours=\([.factoriesFiles[]|select(.ours)|.locatedIn])",
           "ourKeys=\([.ourFactories|keys[]|split(".")[-1]])",
           "老键EnableAutoConfiguration仍在=\(.factoriesCarriesEnableAutoConfiguration)"'
```

返回（实测；本模块只引 webmvc + webflux 两个 starter，所以数字很小、很好核对）：

```
candidateTotal=44  imports文件数=11  factories文件数=12
spring-boot-autoconfigure-4.1.2.jar  12 行
spring-boot-http-codec-4.1.2.jar  1 行
spring-boot-http-converter-4.1.2.jar  1 行
spring-boot-jackson-4.1.2.jar  1 行
spring-boot-netty-4.1.2.jar  1 行
spring-boot-reactor-4.1.2.jar  1 行
spring-boot-reactor-netty-4.1.2.jar  2 行
spring-boot-servlet-4.1.2.jar  5 行
spring-boot-tomcat-4.1.2.jar  5 行
spring-boot-webflux-4.1.2.jar  9 行
spring-boot-webmvc-4.1.2.jar  6 行
ours=["demo-springboot-startup-0.0.1-SNAPSHOT.jar"]
ourKeys=["SpringApplicationRunListener","FailureAnalyzer"]
老键EnableAutoConfiguration仍在=false
```

11 份 `.imports` 的行数相加正好 44，等于 `ImportCandidates` 去重后的候选总数——
说明 Boot 4 把自动配置**拆进了多个 jar**，`spring-boot-autoconfigure` 自己只剩 12 个；
「往 `spring.factories` 追加自动配置类」的老写法在 Boot 4 已彻底失效（最后一行是证据）。

### 2.4 `@Conditional` 家族：21 个注解，11 个类

```bash
curl -s "http://localhost:9014/api/startup/annotations/conditional?includeUserCondition=true" \
  | jq -r '"注解 \(.annotationCount) 个 -> Condition 类 \(.distinctConditionClasses) 个",
           (.conditionToAnnotations|to_entries[] | "\(.key|split(".")[-1])  <-  \(.value|map(sub("Conditional";""))|join(", "))")'
```

返回（实测）：

```
注解 21 个 -> Condition 类 11 个
OnClassCondition  <-  OnClass, OnMissingClass, OnCheckpointRestore
OnBeanCondition  <-  OnBean, OnMissingBean, OnSingleCandidate, OnMissingFilterBean
OnPropertyCondition  <-  OnProperty, OnBooleanProperty, OnProperties, OnBooleanProperties
OnWebApplicationCondition  <-  OnWebApplication, OnNotWebApplication
OnWarDeploymentCondition  <-  OnWarDeployment, OnNotWarDeployment
OnJndiCondition  <-  OnJndi
OnJavaCondition  <-  OnJava
OnCloudPlatformCondition  <-  OnCloudPlatform
OnThreadingCondition  <-  OnThreading
OnExpressionCondition  <-  OnExpression
OnResourceCondition  <-  OnResource
```

`OnPropertyCondition` 的成员实测是 `['name', 'value', 'prefix', 'havingValue', 'matchIfMissing']`——
场景 3 那个开关的全部语义就在这五个参数里。列表末尾还有一行本模块自己写的
`FeatureColorCondition`：用户侧加条件不需要新注解，`@Conditional(自己的类)` 就是同一个根。

### 2.5 容器里 191 个 Bean 分别是谁注册的

```bash
curl -s http://localhost:9014/api/startup/annotations/provenance \
  | jq -r '"total=\(.totalDefinitions)", (.byOrigin|to_entries[] | "\(.key)  \(.value)"),
           "importedBySelector=\(.importedBySelector)"'
```

返回（实测，`byOrigin` 的顺序是「每种来源第一次出现的位置」，不是按数量排）：

```
total=191
框架基础设施  30
其它（显式 register / 内联注册）  1
组件扫描（@ComponentScan）  23
@Import 导入的配置类  27
@Bean 方法（ConfigurationClassBeanDefinitionReader）  90
自动配置导入（.imports → DeferredImportSelector）  20
importedBySelector=["com.example.demo.startup.annotations.feature.StartupFeatureBasicConfig"]
```

- **44 个候选 → 20 个进容器**：其余被条件挡掉（场景 3 的条件报告解释为什么）；
- **`@Import` 导入的配置类，Bean 名是类全限定名**；扫描产物是首字母小写短名；
  `@Bean` 方法产物是方法名——看到一长串 FQN 就知道它是「导入」而不是「扫描」进来的；
- 那 1 个「其它」是主类自己：`SpringApplication` 直接 `register`，既不是扫描也不是导入；
- `excludeFilters` 的证据在同一份返回里：`modeProbeApp` / `lifecycleProbeApp` / `probeApp` /
  `featureProbeApp` 四个「类路径上有=true，容器里没有=已剔除」。

### 2.6 `@Configuration(proxyBeanMethods = false)` 到底改了什么

两段配置代码逐字相同，只差一个参数。标本在 `annotations/proxymethod/`：
`HandleSlave` 每次被 `new` 就自增档位计数，`HandleMaster` 记住拿到的 `identityHashCode`。

```bash
curl -s http://localhost:9014/api/startup/annotations/proxymethods | jq -r '"full: same=\(.full.sameInstance) instances=\(.full.instancesCreated) cglib=\(.full.cglibEnhanced)", "lite: same=\(.lite.sameInstance) instances=\(.lite.instancesCreated) cglib=\(.lite.cglibEnhanced)", "合计创建 HandleSlave=\(.slaveTotalAcrossTiers)"'
```

返回（实测）：

```
full: same=true instances=1 cglib=true
lite: same=false instances=2 cglib=false
合计创建 HandleSlave=3
```

lite **不是**「不注册 Bean」，而是「不代理方法」：两个定义照常存在、照常单例，
只是 `masterLite()` 里那句 `slaveLite()` 不再被拦回容器，于是多出一个不进容器的孤儿实例。

### 2.7 手写一个 `@EnableXxx`

`annotations/feature/` 里有完整的 `@EnableStartupFeature`：`@Import(StartupFeatureSelector.class)` +
一个按 `mode` 返回配置类名的 `ImportSelector`，与 `@EnableConfigurationProperties` 同形。

```bash
curl -s http://localhost:9014/api/startup/annotations/feature \
  | jq -r '(.selectorHistory[] | "\(.importingClass|split(".")[-1])  mode=\(.mode)  imports=\(.imports|map(split(".")[-1])|join(","))"),
           "basic: api=\(.residentBasic.apiBeanNames) propertiesBean数=\(.residentBasic.propertiesBeanNames|length)",
           "full : api=\(.childFull.apiBeanNames) bound=\(.childFull.boundValues|join(" / "))",
           "selectorIsDeferred=\(.autoConfigSelectorIsDeferred)"'
```

返回（实测，首次调用时 `selectorHistory` 是两条）：

```
FeatureEnableDemoConfig  mode=basic  imports=StartupFeatureBasicConfig
FeatureProbeApp  mode=full  imports=StartupFeatureFullConfig
basic: api=["startupFeatureApiBasic"] propertiesBean数=0
full : api=["startupFeatureApiFull"] bound=names=[alpha, beta, gamma] / retries=7 / featureLabel=loose-binding-works
selectorIsDeferred=true
```

- 同一个注解、两种 `mode`，得到两个不同的配置类：`@EnableXxx` 的魔法 = `@Import` + 选择器；
- `retries=7` 不是 yaml 里的 2：命令行参数（`--key=value`）优先级压过 `application.yaml`；
  `names=[alpha, beta, gamma]` 来自 yaml 的逗号串（宽松绑定）；
- `full` 档才出现属性 Bean，名字是 `demo.startup.annotations-com.example…StartupFeatureProperties`
  这种「prefix-FQN」形式——那是 `@EnableConfigurationProperties` 的固定命名，不是随手起的；
- **这个接口不幂等**：每调用一次就多起一个 NONE 子应用、`selectorHistory` 多一条
  （实测两次调用后 2 → 3 条），子应用耗时 207 / 303 / 353ms 浮动。确定性落在 `mode`、`imports` 与绑定值上。

## 3. 场景 1：启动事件时间线

`StartupTimelineRunListener` 注册在 `META-INF/spring.factories`，把 7 个阶段回调写进时间线；
`StartupEventListener`（`@Component`）只能收到容器建立之后的 3 个事件。两者写入同一张表，
返回值里的 `source` 字段就是「RunListener 能看到多早」的分界。

启动控制台（实测片段）：

```
12:25:46.794 INFO  [main] demo.startup.timeline - [environmentPrepared] Environment 就绪（属性源 8 个），命令行参数 0 个 | +432ms | 距启动 432ms | 线程 main

  .   ____          _            __ _ _
 :: Spring Boot ::                (v4.1.2)

12:25:46.876 INFO  [main] demo.startup.timeline - [contextPrepared] 容器已创建未加载：AnnotationConfigServletWebServerApplicationContext | +83ms | 距启动 515ms | 线程 main
12:25:46.939 INFO  [main] demo.startup.timeline - [contextLoaded] Bean 定义已加载，共 6 个（自动配置尚未导入，refresh 后会跳升） | +63ms | 距启动 579ms | 线程 main
12:25:48.601 INFO  [main] o.s.boot.tomcat.TomcatWebServer - Tomcat initialized with port 9014 (http)
12:25:48.686 INFO  [main] o.s.b.w.c.s.WebApplicationContextInitializer - Root WebApplicationContext: initialization completed in 1745 ms
12:25:49.771 INFO  [main] o.s.boot.tomcat.TomcatWebServer - Tomcat started on port 9014 (http) with context path '/'
12:25:49.781 INFO  [main] demo.startup.timeline - [ContextRefreshedEvent] 容器 refresh 完成 | +2841ms | 距启动 3420ms | 线程 main
12:25:49.796 INFO  [main] c.e.demo.startup.StartupApplication - Started StartupApplication in 3.459 seconds (process running for 4.032)
12:25:49.806 INFO  [main] demo.startup.timeline - [ready] 应用就绪，可接收请求，Boot 计时 3480ms | +1ms | 距启动 3445ms | 线程 main
```

两个容易被忽略的实测细节：

- **`starting` 没有日志行**。`LoggingSystem` 在 `environmentPrepared` 里才初始化，所以第一行日志
  就是 `[environmentPrepared]`——阶段 1 只能从接口或事件里看到。
- **Banner 出现在第一行日志之后**。上面第二行才是 Banner，说明日志系统比 Banner 更早接管。

```bash
curl -s http://localhost:9014/api/startup/timeline
```

返回（实测，已按 seq 排列）：

| seq | phase | source | +耗时 | 距启动 |
| --- | --- | --- | --- | --- |
| 1 | starting | RUN_LISTENER | 0ms | 0ms |
| 2 | environmentPrepared | RUN_LISTENER | 432ms | 432ms |
| 3 | contextPrepared | RUN_LISTENER | 83ms | 515ms |
| 4 | contextLoaded | RUN_LISTENER | 63ms | 579ms |
| 5 | ContextRefreshedEvent | CONTEXT_EVENT | 2841ms | 3420ms |
| 6 | ApplicationStartedEvent | CONTEXT_EVENT | 15ms | 3435ms |
| 7 | started | RUN_LISTENER | 2ms | 3438ms |
| 8 | ApplicationReadyEvent | CONTEXT_EVENT | 5ms | 3443ms |
| 9 | ready | RUN_LISTENER | 1ms | 3445ms |

`contextLoaded` 时 **6 个** Bean 定义，`ContextRefreshedEvent` 时 **191 个**（逐条来源见场景 0 的 2.5）：自动配置走
`DeferredImportSelector`，在 refresh 期间才展开注册。别把「Bean 定义数」当成启动期常量。

## 4. 场景 2：启动模式对比

```bash
curl -s http://localhost:9014/api/startup/modes/compare
```

同一套代码起四个子应用（都 `--server.port=0`，随机端口，用完即关），实测：

| mode | 容器实现类 | Bean 定义 | WebServer | 端口 | 子应用耗时 |
| --- | --- | --- | --- | --- | --- |
| NONE | `AnnotationConfigApplicationContext` | 87 | 无 | 无 | 580ms |
| REACTIVE | `AnnotationConfigReactiveWebServerApplicationContext` | 134 | `NettyWebServer` | 51982 | 1571ms |
| SERVLET | `AnnotationConfigServletWebServerApplicationContext` | 158 | `TomcatWebServer` | 51985 | 1743ms |
| AUTO-DEDUCED | `AnnotationConfigServletWebServerApplicationContext` | 158 | `TomcatWebServer` | 51990 | 1676ms |

- NONE 没有 `WebServer`，也没有端口——定时任务、批处理用它是正解。
- AUTO-DEDUCED 结果和 SERVLET 一样，是因为**类路径上 webmvc 排在 webflux 之前**；
  两个 Web 依赖同时存在时推断结果不受你控制，要么删依赖要么显式指定。

端口冲突实验：

```bash
curl -s http://localhost:9014/api/startup/modes/port-in-use
```

```
mode PORT-IN-USE | outcome FAILED | port None | ms 669
failureClass ApplicationContextException
failureMessage Failed to start bean 'webServerStartStop' ｜根因: BindException: Address already in use
               ｜异常链: ApplicationContextException -> PortInUseException -> IllegalArgumentException
                 -> LifecycleException -> BindException ->
```

真实进程端口被占时（本模块默认端口 9006 已被占用后再启动一次，实测）：

```
APPLICATION FAILED TO START
***************************

Description:

Web server failed to start. Port 9006 was already in use.

Action:

Identify and stop the process that's listening on port 9006 or configure this application to listen on another port.
```

整段日志 38 行、堆栈帧 0 条——内置 `PortInUseFailureAnalyzer` 认领了这条异常链。
注意 `Tomcat initialized with port 9006` 仍会先打印：初始化成功、绑定才失败，
所以「日志里有 Tomcat started 前的初始化」不代表端口没问题。

## 5. 场景 3：自动配置揭秘

`ConditionEvaluationReport` **每次启动都会记录**，`--debug`（或 `debug=true`）只决定它打不打印。
本模块用接口把这份报告读出来，避免 500 行刷屏。

```bash
curl -s http://localhost:9014/api/startup/autoconfig
```

默认启动实测：

```
available True  totalSources 111  totalConditions 139  matched 85  unmatched 54  unconditional 6  exclusions []
 - AutoConfigDemoConfig#grayReleaseBean | FeatureColorCondition | matched=False
   | 属性 demo.startup.autoconfig.feature-color 当前值="orange"，判定规则=值包含 green
 - AutoConfigDemoConfig#propertyToggleBean | OnPropertyCondition | matched=False
   | @ConditionalOnProperty (demo.startup.autoconfig.property-toggle=true) found different value in property ...
```

自定义条件（`FeatureColorCondition extends SpringBootCondition`）的优势看得很清楚：
落选原因是一句中文，而不是干巴巴的 `false`。

把开关打开再启动一次对比：

```bash
DEMO_PROPERTY_TOGGLE=true DEMO_FEATURE_COLOR=green SERVER_PORT=9016 \
  java -jar demo-springboot-startup/target/demo-springboot-startup-0.0.1-SNAPSHOT.jar
curl -s 'http://localhost:9016/api/startup/autoconfig/search?contains=AutoConfigDemoConfig'
```

```
totalSources 111  totalConditions 139  matched 87  unmatched 52      ← 相对默认启动 85/54
 - AutoConfigDemoConfig#grayReleaseBean    | FeatureColorCondition | matched=True | ...当前值="green"...
 - AutoConfigDemoConfig#propertyToggleBean | OnPropertyCondition   | matched=True | ...property-toggle=true) matched
```

看控制台原文（同一份报告，只是全量打印）：

```bash
SERVER_PORT=9017 java -jar demo-springboot-startup/target/demo-springboot-startup-0.0.1-SNAPSHOT.jar --debug
```

实测报告骨架：整份日志 506 行，`CONDITIONS EVALUATION REPORT` 在第 41 行，
`Positive matches:` 45 行、`Negative matches:` 240 行、`Exclusions:` 476 行、
`Unconditional classes:` 482 行。自定义条件的中文解释会原样出现在 Negative matches 里。

```bash
# 自动配置类名清单（注意 Boot 4 的包名）
curl -s http://localhost:9014/api/startup/autoconfig/list
```

```
present  org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
present  org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
present  org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
present  org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerAutoConfiguration
absent   org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
```

Boot 4 把自动配置从 `spring-boot-autoconfigure` 拆进各技术模块：
`org.springframework.boot.autoconfigure.web.servlet.*` 这类 Boot 2/3 包名在本模块**已经不存在**，
按旧包名写排除项既不报错也不生效。

反例实验：排除一个自动配置到底会怎样？

```bash
curl -s http://localhost:9014/api/startup/autoconfig/exclude-demo
```

实测（推翻了「容器还在、路由 404」这个常见预期）：

```
baselinePort 52009  baselineBeanCount 158  baselineStatus 200
excludedOutcome FAILED
excludedFailureClass ApplicationContextException
excludedFailureMessage Unable to start web server ｜根因: NoSuchBeanDefinitionException:
  No qualifying bean of type 'org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath' available
costMillis 1069
```

`ErrorMvcAutoConfiguration#errorPageCustomizer` 需要 `DispatcherServletPath`，
而它由被排除的 `DispatcherServletAutoConfiguration` 提供 → 第二个子应用直接起不来。
**自动配置不是孤立开关，是一个有依赖图的集合，排除要连坐评估。**

## 6. 场景 4：Bean 生命周期

```bash
curl -s http://localhost:9014/api/startup/beanlife/trace
```

每次调用都在一个 NONE 子容器里新建一份演示 Bean、调用两次、再 `close()`，
所以能看到完整的 17 条打点记录（16 个不同站点，`target-method` 出现两次），实测顺序：

| seq | stage | 关键实测细节 |
| --- | --- | --- |
| 12 | constructor | 构造注入完成，`fieldDependency=null`——字段注入还没发生 |
| 13 | populate-bean | 字段注入、setter 注入、`@Value` 都在这一步 |
| 14–17 | aware-bean-name / classloader / bean-factory / application-context | 前三个是容器内置回调，第四个由 `ApplicationContextAwareProcessor` 这个 BPP 触发 |
| 18 | bpp-before | `postProcessBeforeInitialization` 返回原对象 |
| 19 | post-construct | **在 bpp-before 之后**：`@PostConstruct` 本身也是 BPP 处理的 |
| 20 | initializing-bean | `afterPropertiesSet` |
| 21 | init-method | `@Bean(initMethod=...)` |
| 22 | bpp-after | 返回 JDK 动态代理，容器从此只认代理 |
| 23 | proxy-invoke | 调用落在 `$Proxy60` 上（只记首次） |
| 24–25 | target-method | 两次 `greet()` 都到达真实对象 |
| 26–28 | pre-destroy / disposable-bean / destroy-method | 只有 `context.close()` 才会执行 |

```
greetResult: hello from bean 'lifecycleDemoBean' via lifecycle-service
             (fieldInjected=true, setterInjected=true, tag=RUN-1793241329958, ready=READY)
```

对照接口（常驻实例，只有 11 个站点）：

```bash
curl -s http://localhost:9014/api/startup/beanlife/resident
```

主容器里那个 Bean 从 seq 1 到 11 停在 `bpp-after`——**没有销毁站点**，因为进程还活着。
这正好演示「销毁只发生在 `close()`」。注意 `aware-classloader` 那行：
常驻实例是 `LaunchedClassLoader`，子容器实例是 `TomcatEmbeddedWebappClassLoader`。

按接口取 Bean 是必需的：`context.getBean(LifecycleDemoBean.class)` 会 `ClassCastException`，
容器里存的代理只实现 `LifecycleDemoApi`。

## 7. 场景 5：启动失败双结局

```bash
# A. 有 analyzer 认领：三行可执行报告
DEMO_FAIL_MODE=analyzer java -jar demo-springboot-startup/target/demo-springboot-startup-0.0.1-SNAPSHOT.jar

# B. 无人认领：满屏堆栈
DEMO_FAIL_MODE=raw java -jar demo-springboot-startup/target/demo-springboot-startup-0.0.1-SNAPSHOT.jar
```

| 输出 | 日志行数 | 堆栈帧 | `Caused by` | 首行 |
| --- | --- | --- | --- | --- |
| analyzer | 42 | 0 | 0 | `APPLICATION FAILED TO START` |
| raw | 63 | 30 | 3 | `ERROR o.s.boot.SpringApplication - Application run failed` |

analyzer 那次实测报告原文：

```
Description:

配置项取值非法：
  demo.startup.fail.mode=analyzer

Action:

把配置项改为 none 正常启动；analyzer/raw 只用于演示两种失败输出。临时验证命令见 README 第 6 节

（本报告由自定义 StartupConfigFailureAnalyzer 生成；同一异常若没有认领的 analyzer，
控制台会打印完整堆栈——见场景 5 的 raw 对照组）
```

raw 那次的异常链是 3 层 `Caused by`：
`IllegalStateException（没有分析器认领的裸异常）→ RuntimeException（底层调用失败）→ ConnectException（connection refused (simulated)）`。
`FailureAnalyzer` 靠泛型声明的异常类型在 cause 链上下钻认领——裸抛一个没人认领的异常，
报告就退回堆栈墙。

两次运行都以 `exit=1` 结束，且时间线里都有一条 `[failed]` 日志（`SpringApplicationRunListener.failed()`）。

## 8. 测试

```bash
./mvnw -pl demo-springboot-startup -am test
```

23 个用例，全部本机运行、不需要任何中间件：

- `StartupTimelineTest`：`containsExactly` 断言阶段顺序，并验证 RunListener 与事件监听器交叉写入；
- `BeanLifecycleTest`：断言 17 个站点的完整顺序与代理替换证据；
- `StartupModesTest`：三种模式的容器类 / WebServer / 端口，以及端口冲突必然 FAILED；
- `AutoConfigReportTest`：报告可读、条件默认关闭、自定义条件的中文消息被记录，
  以及「排除 DispatcherServletAutoConfiguration 导致启动失败」的回归；
- `StartupConfigFailureAnalyzerTest`：缺失值 / 非法值两种文案，以及别人家的异常不被认领；
- `AnnotationMechanicsTest`：场景 0 的七条断言——17 行映射的类都可见、三合一元注解、
  SPI 候选数（44）、条件注解分桶、四种来源分得开、`proxyBeanMethods` 两档差值、
  `@EnableStartupFeature` 的选择器链路。

## 9. 代码地图

| 文件 | 职责 |
| --- | --- |
| `StartupApplication.java` | 唯一入口；`excludeFilters` 挡掉 3 个探针应用 + 2 个 feature 配置 + 1 个 `@Enable` 宿主（否则与 `@Import` 撞名） |
| `annotations/AnnotationClassMapService.java` | 场景 0：17 行「注解 → 干活类」对照表，逐行 `Class.forName` 验证 |
| `annotations/AnnotationTreeService.java` | 场景 0：JDK 递归元注解树 + `MergedAnnotations` 合并视图、SearchStrategy 计数、别名对照 |
| `annotations/SpiFileService.java` | 场景 0：扫全类路径的 `.imports` 与 `spring.factories`，报候选总数与归属 |
| `annotations/ConditionFamilyService.java` | 场景 0：21 个条件注解按 Condition 类分桶（反查表） |
| `annotations/BeanProvenanceService.java` | 场景 0：191 个 Bean 定义的来源分类、CGLIB 增强标记、`excludeFilters` 证据 |
| `annotations/ProxyMethodService.java` + `proxymethod/` | 场景 0：`proxyBeanMethods` 两档标本，计数器 + `identityHashCode` |
| `annotations/FeatureDemoService.java` + `feature/` | 场景 0：手写 `@EnableStartupFeature`、`ImportSelector`、属性注册与绑定 |
| `timeline/StartupTimelineRunListener.java` | 7 个阶段回调；注册在 `META-INF/spring.factories` |
| `timeline/StartupTimelineCollector.java` | 静态时间线（RunListener 不是 Bean，只能这样搭桥） |
| `timeline/StartupEventListener.java` | 对照组：`@EventListener` 只能收到容器建立后的 3 个事件 |
| `modes/StartupModesService.java` | 起 NONE / SERVLET / REACTIVE / AUTO-DEDUCED 四个子应用并采集快照 |
| `modes/ModeSnapshot.java` | 快照 record，含失败时的根因与异常链 |
| `autoconfig/AutoConfigService.java` | 读 `ConditionEvaluationReport`；排除实验 `excludeDemo()` |
| `autoconfig/FeatureColorCondition.java` | 自定义条件：`SpringBootCondition.getMatchOutcome` 里给出中文判定依据 |
| `autoconfig/AutoConfigDemoConfig.java` | 三种条件形态宿主（开关注解 / 自定义 / 无条件） |
| `autoconfig/probe/` | 排除实验的探针应用与路由 |
| `beanlife/LifecycleDemoBean.java` | 生命周期标本：实例化/初始化/销毁三类回调全部打点 |
| `beanlife/LifecycleTraceProcessor.java` | `BeanPostProcessor`：只包装演示 Bean，实测换 JDK 代理 |
| `beanlife/LifecycleTraceRecorder.java` | 静态轨迹记录 + `instanceKey` 序号隔离多次运行 |
| `beanlife/LifecycleBeanLifeService.java` | NONE 子容器实验编排：取代理、调用两次、`close()` 后再取轨迹 |
| `failure/StartupFailureGuard.java` | `@PostConstruct` 按 `DEMO_FAIL_MODE` 抛「有人认领」/「没人认领」两种异常 |
| `failure/StartupConfigFailureAnalyzer.java` | 自定义 `AbstractFailureAnalyzer`，把异常翻成 DESCRIPTION / ACTION |
| `controller/` | 索引、时间线、模式/自动配置、Bean 生命周期、注解探针五组只读接口 |
| `src/test/java/...` | 6 个测试类，23 个用例 |

深入阅读：[docs/springboot-startup-guide.html](../docs/springboot-startup-guide.html)（心智模型、机制图、逐场景实测与坑）。
