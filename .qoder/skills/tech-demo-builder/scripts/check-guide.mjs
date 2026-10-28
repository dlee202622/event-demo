#!/usr/bin/env node
// 校验单文件 HTML 教程的结构、锚点、复制按钮与离线约束。
// 用法: node check-guide.mjs <guide.html> [--allow-placeholders]

import fs from 'node:fs';

const args = process.argv.slice(2);
const file = args.find((arg) => !arg.startsWith('-'));
const allowPlaceholders = args.includes('--allow-placeholders');

if (!file) {
  console.error('用法: node check-guide.mjs <guide.html> [--allow-placeholders]');
  process.exit(2);
}
if (!fs.existsSync(file)) {
  console.error(`文件不存在: ${file}`);
  process.exit(2);
}

const html = fs.readFileSync(file, 'utf8');
const failures = [];
const warnings = [];

function check(condition, message) {
  if (!condition) failures.push(message);
}

function count(pattern) {
  return (html.match(pattern) || []).length;
}

check(/<html\b[^>]*\blang=/i.test(html), '缺少 <html lang="...">');
check(/<meta[^>]+name="viewport"/i.test(html), '缺少 viewport meta');
check(/<title>[^<]+<\/title>/i.test(html), '缺少或空的 <title>');
check(count(/<h1\b/gi) === 1, `<h1> 数量应为 1，实际 ${count(/<h1\b/gi)}`);
check(count(/<style\b/gi) >= 1, '缺少内联 <style>');
check(count(/<script\b/gi) >= 1, '缺少内联 <script>');

const ids = new Set();
for (const match of html.matchAll(/\sid="([^"]+)"/g)) {
  if (ids.has(match[1])) failures.push(`重复 id: ${match[1]}`);
  ids.add(match[1]);
}

const tocStart = html.indexOf('class="toc"');
const tocEnd = tocStart >= 0 ? html.indexOf('</nav>', tocStart) : -1;
const toc = tocStart >= 0 && tocEnd > tocStart ? html.slice(tocStart, tocEnd) : '';
check(toc !== '', '缺少侧栏目录 nav.toc');
const tocLinks = [...toc.matchAll(/href="#([^"]+)"/g)].map((m) => m[1]);
if (allowPlaceholders) {
  warnings.push('模板模式：跳过目录链接数量与锚点存在性检查');
} else {
  check(tocLinks.length >= 3, `目录链接过少: ${tocLinks.length}`);
  const missing = [...new Set(tocLinks.filter((id) => !ids.has(id)))];
  check(missing.length === 0, `目录锚点不存在: ${missing.join(', ')}`);
}

const copyButtons = count(/\bdata-copy\b/g);
check(!/data-tip/.test(html), '发现 data-tip，应为 data-copy');

// 代码块必须带注释；含 curl 的终端块后面必须紧跟执行结果块（.code.out）
const blocks = [...html.matchAll(
  /<div class="code([^"]*)">[\s\S]*?<span class="path">([^<]*)<\/span>[\s\S]*?<pre><code>([\s\S]*?)<\/code><\/pre>/g
)].map((match) => ({
  classes: match[1],
  label: match[2].trim(),
  body: match[3]
}));
const codeBlocks = blocks.length;
if (codeBlocks > 0 && copyButtons < codeBlocks) {
  failures.push(`代码块 ${codeBlocks} 个，但复制按钮只有 ${copyButtons} 个（每块应有一个 data-copy）`);
}
const isResult = (block) => block.classes.split(/\s+/).includes('out');
const hasInlineComment = (block) => /(^|\n)\s*(#|\/\/|--)\s|\s(#|\/\/)\s/.test(block.body);

blocks.forEach((block, index) => {
  if (isResult(block)) return;
  const lines = block.body.split('\n').filter((line) => line.trim() !== '');
  if (lines.length >= 3 && !hasInlineComment(block)) {
    failures.push(`代码块缺少行内注释: ${block.label}`);
  }
  if (/\bcurl\b/.test(block.body)) {
    const next = blocks[index + 1];
    if (!next || !isResult(next)) {
      failures.push(`curl 命令后缺少执行结果块: ${block.label}`);
    }
  }
});
if (!allowPlaceholders && blocks.length > 0) {
  const resultCount = blocks.filter(isResult).length;
  check(resultCount > 0, '没有任何 .code.out 执行结果块，读者看不到真实返回');
}

const external = [
  ...html.matchAll(/<(?:script|img|iframe)\b[^>]*\bsrc="(https?:)?\/\//gi),
  ...html.matchAll(/<link\b[^>]*\bhref="(https?:)?\/\//gi),
  ...html.matchAll(/url\(\s*['"]?(https?:)?\/\//gi)
];
check(external.length === 0, `存在外部资源引用 ${external.length} 处，教程必须离线可用`);

if (!allowPlaceholders) {
  check(!/{{[^}]+}}/.test(html), '残留 {{占位符}}');
  check(!/\b(TODO|FIXME|TBD)\b/.test(html), '残留 TODO/FIXME/TBD');
}

for (const tag of ['div', 'section', 'main', 'aside', 'header', 'footer', 'table']) {
  const open = count(new RegExp(`<${tag}\\b`, 'gi'));
  const close = count(new RegExp(`</${tag}>`, 'gi'));
  check(open === close, `<${tag}> 标签未配平: ${open} 开 / ${close} 闭`);
}

const progress = /class="progress"/.test(html);
const theme = /id="themeToggle"/.test(html);
if (!progress) warnings.push('没有阅读进度条 (.progress)');
if (!theme) warnings.push('没有主题切换按钮 (#themeToggle)');
if (!/data-theme="(light|dark)"/.test(html)) warnings.push('html 上没有 data-theme 初始值');

console.log(`tech-demo-builder · HTML 校验: ${file}`);
console.log(`代码块: ${codeBlocks}  复制按钮: ${copyButtons}  结果块: ${blocks.filter(isResult).length}  目录链接: ${tocLinks.length}`);

if (warnings.length > 0) {
  console.log('');
  for (const warning of warnings) console.log(`WARN  ${warning}`);
}

if (failures.length > 0) {
  console.log('');
  for (const failure of failures) console.log(`FAIL  ${failure}`);
  console.log(`\n结果: 未通过 (${failures.length} 项)`);
  process.exit(1);
}

console.log('\n结果: 通过');
