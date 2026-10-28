---
name: tech-demo-builder
description: 把一个技术主题（中间件、框架、协议、算法）扩展成可运行的教学 demo：先设计案例矩阵，再生成带详细教学注释的代码、验证实测结果，最后产出 README 与单文件 HTML 教程。当用户说“帮我加 XX 的 demo / 案例 / 学习文档”“代码注释不够”“整理成教程”，或要为本仓库新增 demo-* 模块时使用。
---

# 技术教学 Demo 构建

用户只给一个技术名时，不要立刻写代码。先把“学什么”拆成可观察的案例，再实现、实测、成文。

## 核心原则

1. **先场景后代码**：先给案例矩阵（核心 / 对比 / 边界 / 故障），确认后再动手。
2. **每个结论可复现**：教程里的数字必须来自本机实测，不能编造；实验要确定、可重复。
3. **注释解释为什么**：教学代码的目标是初学者不查资料也能读懂；禁止“设置值”式复述。
4. **复用现有基础设施**：优先用仓库已有的 Docker Compose、数据库、端口规范，不新增无必要依赖。
5. **一份权威文档**：模块 README 管运行与接口，HTML 教程管理解与原理；两者不互相复制大段内容。

## 流程

### 1. 定题与边界

- 确认：技术/中间件及版本、要覆盖的场景、是否接现有基础设施、读者水平。
- 一次最多问 3 个问题；其余用推荐默认值并在方案里说明。
- 输出 3–6 个场景的候选清单，标明每个场景“能观察到什么”，等用户确认。

### 2. 调研仓库

- 读根 README、`docker-compose.yml`、相邻 `demo-*` 模块的 pom、配置、文档风格。
- 端口约定：9001–9003 是用户在 IDEA 里常驻的服务，**不要占用、不要 kill**；新模块从 9004 起顺延。
- 构建只用 `./mvnw`。

### 3. 案例矩阵

按 [references/demo-design.md](references/demo-design.md) 产出表格，至少覆盖：

- 1–2 个核心场景（机制怎么工作）
- 1 个对比场景（两种实现/算法差异）
- 1 个边界场景（参数临界、窗口交界、容量上限）
- 1 个故障/竞态场景（去掉保护会怎样）

每个场景必须写清：触发命令、预期观察、失败/反例、实测数字。

### 4. 模块骨架

按 [references/module-conventions.md](references/module-conventions.md) 建模块：

- `demo-<topic>` + 根 pom `<module>` + Docker Compose 依赖（如需要）
- `application.yaml` 全部关键项支持环境变量覆盖
- `schema.sql` / `data.sql` 可重复执行
- 包结构：`config` / `controller` / `<domain>` / `support`
- 测试优先 Testcontainers 或独立库表，不依赖本机常驻服务

### 5. 代码与注释

写代码前先读 [references/comment-standard.md](references/comment-standard.md)。硬性要求：

- 类级注释：定位 + 适用场景 + 关键实现 + 坑 + 替代方案。
- 公共方法：参数单位、返回字段、失败/边界行为。
- 分支、magic number、并发/顺序不变量：逐处解释“为什么”。
- Lua/脚本：每个分支和返回值格式都要有注释。
- 注释用中文，标识符保持英文；核心算法类注释行占比 ≥ 25%，控制器 ≥ 12%。

写完运行：

```bash
node .qoder/skills/tech-demo-builder/scripts/comment-audit.mjs demo-<topic>/src/main/java
```

### 6. 验证

- `./mvnw -pl demo-<topic> -am test` 通过。
- 用隔离端口、独立 Redis DB、独立 MySQL schema 启动，逐个接口发请求。
- 每个案例至少跑两遍，确认结果稳定；竞态实验要能稳定复现。
- 记录真实输出，作为 README 与 HTML 中“实测”表格的数据来源。
- 验证完关闭自己启动的实例，不动用户的 IDEA 进程。

### 7. 文档

- 模块 `README.md`：启动方式、环境变量、接口清单、每个案例的实测结果、代码地图。
- HTML 教程：按 [references/html-guide-spec.md](references/html-guide-spec.md)，以 [assets/guide-template.html](assets/guide-template.html) 为外壳。
- 每个案例必须成对出现：带行内注释的代码/命令块 + 紧跟其后的 `.code.out` 真实返回；只有裸代码没有结果视为未完成。
- 根 README 增加模块入口；`api-examples.http` 增加可执行请求段。
- 运行：

```bash
node .qoder/skills/tech-demo-builder/scripts/check-guide.mjs docs/<topic>-guide.html
```

### 8. 自检清单

- [ ] 每个场景都有一条可复制命令和真实输出
- [ ] 注释审计通过，核心文件达到密度要求
- [ ] 测试通过，验证实例已关闭
- [ ] HTML 在浅色/深色、窄屏（约 830px）下检查过
- [ ] 根 README、模块 README、HTML 的链接互相可达
- [ ] 未提交 git（除非用户明确要求）

## 资源

- [references/demo-design.md](references/demo-design.md)：案例矩阵模板与可观察性要求
- [references/comment-standard.md](references/comment-standard.md)：教学注释规范、正反例、审计方法
- [references/module-conventions.md](references/module-conventions.md)：模块骨架、端口、配置、测试约定
- [references/html-guide-spec.md](references/html-guide-spec.md)：HTML 教程章节、图表、交互与校验
- `scripts/comment-audit.mjs`：统计注释率并列出未注释的公共方法
- `scripts/check-guide.mjs`：校验标题、锚点、复制按钮、占位符与外部资源
- `assets/guide-template.html`：可直接改写的教程外壳（侧栏 / 复制 / 深浅色 / 响应式）

## 常见坑

- Spring Boot 4 使用 Jackson 3：`tools.jackson.databind.ObjectMapper`，不是 `com.fasterxml`。
- `StringRedisTemplate` 的参数会被序列化成字符串；Lua 的 `tonumber(ARGV[n])` 要求调用方先转字符串。
- Lua 返回值统一成 `允许|剩余|重试毫秒` 这类单字符串，避免客户端对数组的映射差异。
- Redisson 的 `tryLock(wait, leaseTime, unit)` 一旦指定 leaseTime，看门狗不再续期。
- 固定窗口在窗口交界会双倍放行；滑动窗口类算法不会，但内存/精度不同。
- HTML 里的代码片段必须转义 `<`、`>`、`&`，复制按钮统一用 `data-copy`。
