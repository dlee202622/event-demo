#!/usr/bin/env node
// 统计 Java 教学代码的注释密度，并列出缺少注释的公共方法。
// 用法: node comment-audit.mjs <目录> [--strict] [--min=20]

import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const target = args.find((arg) => !arg.startsWith('-')) || '.';
const strict = args.includes('--strict');
const minArg = args.find((arg) => arg.startsWith('--min='));
const globalMin = minArg ? Number(minArg.split('=')[1]) : null;

if (!fs.existsSync(target)) {
  console.error(`目录不存在: ${target}`);
  process.exit(2);
}

function thresholdFor(file) {
  if (globalMin !== null) return globalMin;
  const name = path.basename(file);
  const dir = path.dirname(file);
  if (/Controller\.java$/.test(name)) return 12;
  if (/(record|dto|Response|Request|Result|Decision|Info|Event|Step|Product)\.java$/.test(name)) return 0;
  if (/\/(ratelimit|lock|cache|types|support)\//.test(dir.replaceAll(path.sep, '/'))) return 25;
  if (/(Service|Lock|Limiter|Queue|Scripts)\.java$/.test(name)) return 25;
  if (/\/(config|repository)\//.test(dir.replaceAll(path.sep, '/'))) return 10;
  return 10;
}

function isComment(line) {
  const text = line.trim();
  return text.startsWith('//') || text.startsWith('/*') || text.startsWith('*') || text.startsWith('*/');
}

function isAnnotation(line) {
  return line.trim().startsWith('@');
}

function isBlank(line) {
  return line.trim() === '';
}

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'target' || entry.name === 'node_modules') continue;
      walk(full, out);
    } else if (entry.name.endsWith('.java')) {
      out.push(full);
    }
  }
  return out;
}

function uncommentedMethods(lines) {
  const flags = [];
  const declaration = /^\s*public\s+(?!class\b|record\b|interface\b|enum\b|@interface\b)(?:static\s+)?(?:final\s+)?[\w$.<>\[\], ?]+\s+(\w+)\s*\(/;
  const constructor = /^\s*public\s+([A-Z]\w*)\s*\(/;
  lines.forEach((line, index) => {
    const match = line.match(declaration) || line.match(constructor);
    if (!match) return;
    let documented = false;
    for (let back = index - 1; back >= 0 && back >= index - 6; back--) {
      const candidate = lines[back];
      if (isBlank(candidate) || isAnnotation(candidate)) continue;
      if (isComment(candidate)) documented = true;
      break;
    }
    if (!documented) {
      flags.push({ line: index + 1, signature: line.trim().replace(/\s*\{$/, '') });
    }
  });
  return flags;
}

const files = walk(target);
const rows = [];
let totalLines = 0;
let totalComments = 0;

for (const file of files) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const commentLines = lines.filter(isComment).length;
  const total = lines.length;
  const threshold = thresholdFor(file);
  const density = total === 0 ? 0 : (commentLines / total) * 100;
  const methods = total >= 30 ? uncommentedMethods(lines) : [];
  rows.push({ file: path.relative(target, file), total, commentLines, density, threshold, methods });
  totalLines += total;
  totalComments += commentLines;
}

rows.sort((a, b) => (a.density - a.threshold) - (b.density - b.threshold));

const violations = rows.filter((row) => row.threshold > 0 && row.total >= 40 && row.density < row.threshold);
const undocumented = rows.flatMap((row) => row.methods.map((m) => ({ file: row.file, ...m })));

console.log('tech-demo-builder · 注释审计');
console.log(`目录: ${target}`);
console.log(`文件: ${rows.length}  总行数: ${totalLines}  注释行: ${totalComments}  总体注释率: ${((totalComments / Math.max(1, totalLines)) * 100).toFixed(1)}%`);
console.log('');

if (violations.length > 0) {
  console.log(`低于阈值 (${violations.length}):`);
  for (const row of violations.slice(0, 20)) {
    console.log(`  ${row.density.toFixed(1).padStart(5)}% < ${String(row.threshold).padStart(3)}%  ${row.file}  (${row.commentLines}/${row.total})`);
  }
  if (violations.length > 20) console.log(`  ... 还有 ${violations.length - 20} 个`);
} else {
  console.log('低于阈值: 无');
}

if (undocumented.length > 0) {
  console.log('');
  console.log(`未注释的公共方法 (${undocumented.length}):`);
  for (const item of undocumented.slice(0, 30)) {
    console.log(`  ${item.file}:${item.line}  ${item.signature}`);
  }
  if (undocumented.length > 30) console.log(`  ... 还有 ${undocumented.length - 30} 个`);
} else {
  console.log('未注释的公共方法: 无');
}

const needsWork = violations.length > 0 || (strict && undocumented.length > 0);
if (strict && needsWork) process.exit(1);
