# 项目长期记忆

## 文档建设约定（tech-demo-builder）

- 中间件教程统一放 `docs/<topic>-guide.html`：单文件、离线可用、以 skill 的 guide-template.html 为外壳；复现脚本放 `docs/<topic>-guide-labs/`；根 README 每篇加入口段。
- 已有教程：messaging-guide.html（RabbitMQ/RocketMQ）、redis-guide.html、mysql-guide.html（2026-10-29，含 labs 脚本）。
- 教程数字必须本机实测，来源标注「命令 + 实测日期」；check-guide.mjs 只认行首 `--`/`#`/`//` 注释。
- 实验用独立 schema `demo_mysql_principle`，不碰业务库（demo_order / demo_inventory / demo_loyalty）。
- 端口约定：9001-9003 常驻服务（IDEA），9004 demo-redis，新模块从 9005 起顺延。

## 环境

- 本机 MySQL：docker compose 服务 `mysql`（mysql:8.4.13，root/123456，3306，页 16KB，BP 128MB）。
- Docker Desktop 需手动 `open -a Docker` 启动。
