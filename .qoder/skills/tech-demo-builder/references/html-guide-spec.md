# 单文件 HTML 教程规范

## 交付物

- 路径：`docs/<topic>-guide.html`，一个文件包含全部样式、脚本、SVG。
- 不引用 CDN、外部字体、外部图片；离线打开必须完整可用。
- 以 `assets/guide-template.html` 为外壳，替换标题、目录、正文与页脚。

## 页面结构

```
<html data-theme="light|dark">
├── <head>
│   ├── meta viewport / color-scheme
│   ├── 主题恢复脚本（localStorage，失败回退系统偏好）
│   └── <style> CSS 变量 + 布局 + 组件 + 打印样式
├── <body>
│   ├── svg.svg-defs（箭头 marker 定义）
│   ├── .progress（阅读进度）
│   ├── .theme-toggle
│   └── .shell
│       ├── aside.sidebar
│       │   ├── .brand（领域名 / 主标题 / 一句话）
│       │   ├── nav.toc（编号章节 + .sub 子项）
│       │   └── .side-note（数据来源说明）
│       └── main > .content
│           ├── header.hero（心智模型 + badges + stats + jump）
│           ├── section × N
│           └── footer（来源、核对日期）
└── <script> 主题切换 / 代码复制 / 目录高亮 / 阅读进度
```

## 章节安排

1. **开始之前**：环境、版本、一条最短启动命令、本文约定。
2. **心智模型**：用一句话纠正最常见的误解，并给出全局图。
3. **案例正文**：每个机制一节，按“一句话定义 → 类比 → 图 → 代码 → 实测 → 坑”展开。
4. **边界与反例**：把错误结果放在显眼位置，用表格对比。
5. **运行与速查**：启动、环境变量、常用 curl、参数范围。
6. **代码地图**：文件路径 + 一句话职责，方便跳读源码。

## 单个案例的写法

每个案例固定回答六个问题：

1. 它解决什么问题？（一句话）
2. 生活类比是什么？（一句话，降低门槛）
3. 机制图长什么样？（SVG）
4. 核心代码在哪？（可复制片段，10–30 行，**每段都要有行内注释**）
5. 跑出来是什么？（可复制的命令 + **一段真实返回**，不能只写结论）
6. 什么时候会坏？（边界、并发、配置坑）

典型结构：

```html
<section id="rl-fixed">
  <h2>3.1 固定窗口</h2>
  <p class="lead">把时间切成对齐的格子，每格一个计数器。</p>
  <div class="callout">类比：地铁闸机按整点清零，59 分和 01 分各放一批。</div>
  <div class="diagram"><svg>...</svg></div>
  <div class="code">...<button data-copy>复制</button></div>
  <div class="code out">...执行结果...</div>
  <div class="table-wrap"><table>实测结果...</table></div>
  <div class="callout warn">坑：窗口交界可能双倍放行。</div>
</section>
```

## 视觉与交互

- 颜色只通过 CSS 变量使用，`html[data-theme="dark"]` 覆盖；禁止在正文写死浅色背景。
- SVG 使用 `viewBox`，箭头引用 `#ar` / `#ar-accent` / `#ar-ok` / `#ar-danger`。
- 代码块：`<div class="code">` + `.code-head`（语言标签 + `<button data-copy>`）+ `<pre><code>`。
- 代码块内必须有行内注释：Lua / Shell / Redis 命令用 `#` 或 `--`，Java 用 `//`；不允许整块裸代码。
- 执行结果单独放 `<div class="code out">`：`.code-head` 写「执行结果 · 命令」，内容贴真实返回。
  可以截断长数组、省略字段，但要在标题或首行注明「已省略」；禁止编造数字。
- 每个含 `curl` 的终端块后面必须紧跟一个 `.code.out` 结果块，否则读者不知道跑出来是什么。
- 代码中的 `<`、`>`、`&` 必须转义；复制按钮只认 `data-copy`。
- 表格用 `.table-wrap` 包裹，窄屏可横向滚动。
- 重点结论用 `.callout`，风险用 `.callout.warn` 或 `.callout.danger`。
- 侧栏目录的每个 `href="#id"` 必须有对应元素 id；子项放在 `.sub` 里。
- 窄屏（约 830px）下侧栏折叠为顶部导航，正文单列。

## 写作风格

- 中文、短句、先结论后解释。
- 每个数字都标明来源（命令 + 参数 + 实测日期），不写“大约很快”。
- 术语首次出现给英文原词，例如“看门狗（watchdog）”。
- 不写营销式形容；用对比和数字制造说服力。
- 代码片段与仓库当前实现一致，改了代码要同步教程。

## 校验

```bash
node .qoder/skills/tech-demo-builder/scripts/check-guide.mjs docs/<topic>-guide.html
```

脚本检查：

- 单个 `<h1>`、`lang`、`viewport`、`<title>`
- 目录锚点是否都存在，id 是否重复
- `.code` 是否有复制按钮，是否误用 `data-tip`
- 非结果代码块是否有行内注释
- 含 `curl` 的终端块后是否紧跟 `.code.out`
- 是否残留 `TODO` / `{{占位符}}`
- 是否引用外部 http(s) 资源
- 常见容器标签是否配平

人工检查：

- [ ] 浅色 / 深色各看一遍
- [ ] 830px 窄屏无横向溢出
- [ ] 每张 SVG 文字不重叠、箭头指向正确
- [ ] 复制按钮可用，复制内容与显示一致
- [ ] 随机抽 3 个输出块，按上面命令重跑能复现
- [ ] 页脚核对日期已更新
