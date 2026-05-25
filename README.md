# CloudPassage

基于 Spring Boot、Spring AI Alibaba 和 Vue 3 的多 Agent 图文创作平台。项目面向“选题 -> 大纲 -> 正文 -> 配图 -> 图文合成”完整创作链路，支持任务恢复、失败节点重试、执行观测、节点重放和长期记忆增强。

## 项目简介

这个项目的核心目标，不是简单调用一次大模型生成文章，而是把内容生成过程拆成多个可编排、可观测、可恢复的 Agent 节点：

- 标题生成：生成多组候选标题供用户选择
- 大纲生成：按标题输出结构化大纲，支持人工调整和 AI 修改
- 正文生成：基于大纲流式生成正文内容
- 图片分析与生成：识别配图需求，按策略选择图片方法并支持失败降级
- 图文合成：将正文和图片结果合并为最终文章

当前版本重点补齐了 Agent 工程化能力，包括：

- 节点级执行观测：`node / status / elapsedMs / decisionSummary`
- 失败节点重试与任务恢复
- 节点重放调试面板
- 图片策略路由与降级链路可解释化
- 长期记忆第一版：历史成功 / 失败案例召回参与决策

## 技术栈

### 后端

- `Spring Boot 3.5`
- `Spring AI Alibaba 1.1`
- `MyBatis-Flex`
- `MySQL`
- `Redis + Redisson`
- `DashScope / 通义千问`
- `Tencent COS`

### 前端

- `Vue 3`
- `TypeScript`
- `Vite`
- `Ant Design Vue`
- `Pinia`

## 核心亮点

### 1. 多 Agent 编排，而不是单次 Prompt 生成

项目将标题、大纲、正文、图片分析、图片生成拆分为独立节点，通过统一工作流串联，便于扩展、调试和恢复。

### 2. 可观测、可重试、可重放

后端会记录节点级日志、输入输出摘要、模型配置、决策来源和失败原因；前端可查看节点时间线、调试面板，并对失败节点进行重试。

### 3. 长期记忆参与 Agent 决策

项目已实现结构化长期记忆第一版，能够召回用户历史成功 / 失败创作经验，并实际作用于正文评审、图片策略路由、图片需求分析和图片生成链路。

### 4. 图片链路具备降级能力

支持多种配图方式与降级路由，避免单一图片方法失败导致整条创作链路中断。

## 目录结构

```text
src/main/java                Spring Boot 后端主代码
src/main/resources          后端配置与资源文件
frontend/src                Vue 3 前端代码
sql/                        数据库脚本
项目优化计划/               分阶段升级方案与实施记录
go-backend/                 Go 版本后端实验目录
python-backend/             Python 版本后端实验目录
```

## 本地启动

### 方式一：Docker Compose

适合快速启动完整环境：

```bash
docker compose up -d --build
```

停止服务：

```bash
docker compose down
```

### 方式二：本地开发模式

环境要求：

- `JDK 21+`
- `Node.js 18+`
- `MySQL 8+`
- `Redis`

1. 初始化数据库

```bash
mysql -uroot -p < sql/create_table.sql
```

2. 配置环境

- 复制并修改 `.env.example` 或 `src/main/resources/application-local.yml.example`
- 配置必要的模型、图片和对象存储参数

3. 启动后端

```bash
mvn spring-boot:run
```

4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

## 常用命令

```bash
# 后端编译
mvn -DskipTests compile

# 后端测试
mvn test

# 前端构建
cd frontend && npm run build

# 前端本地开发
cd frontend && npm run dev
```

## 当前阶段重点能力

当前主线已经从“基础图文生成”升级到“Agent 工程化能力建设”，重点包括：

- 执行内核收敛
- 节点级日志与时间线观测
- 失败节点重试
- 任务恢复与状态快照
- 节点重放调试
- 长期记忆第一版
- 图片路由 / 图片生成链路可解释化

## 说明

- 仓库当前以 `main` 作为对外展示主分支
- `项目优化计划/` 中保留了分阶段设计与实施文档，适合了解演进过程
- 本项目更适合作为 AI 全栈 / AI Agent 工程实践项目，而不是单纯的页面展示项目
