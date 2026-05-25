# Prompt 与模型参数配置化设计

## 文档目标

这份文档只聚焦第四阶段的第一优先级：先把 Java 主后端里的 Prompt、模型与推理参数做成可配置、可记录、可渐进迁移的结构。

目标不是一步做成复杂的 Prompt 平台，而是先解决三个现实问题：

- 调 Prompt 需要改代码，试错成本高
- 不同 Agent 不能独立调整模型参数
- 日志里看不到本次节点到底用了哪套 Prompt 和参数

## 当前现状

结合当前代码，现状比较明确：

- `PromptConstant` 仍是主 Prompt 来源
- `PromptConfig` 已存在，但目前只支持 `Map<String, String>` 级别的模板覆盖
- `AgentConfig` 只负责 `orchestrator.enabled` 和 `max-iterations`
- `TitleGeneratorAgent`、`OutlineGeneratorAgent`、`ContentGeneratorAgent`、`ImageAnalyzerAgent` 直接拼接 `PromptConstant`
- 当前节点日志没有记录 `promptKey / promptVersion / model / temperature / maxTokens`

这意味着系统虽然“能运行”，但还不具备真正的 Agent 研发调参能力。

## 本次最小改造目标

这一轮先做到 4 件事：

### 1. Prompt 可按 key 和版本统一读取

不再让 Agent 直接依赖 `PromptConstant.xxx`，而是统一通过配置服务获取：

- `agent1_title`
- `agent2_outline`
- `agent2_description_section`
- `agent3_content`
- `agent4_image`
- 各种 style prompt

### 2. 每个核心 Agent 都能有独立配置

至少支持以下字段：

- `model`
- `temperature`
- `maxTokens`
- `topP`
- `promptKey`
- `promptVersion`
- `structuredOutput`
- `streaming`

### 3. 保留默认兜底，避免一次改坏

迁移期间要满足：

- 配置没写时，仍回退到 `PromptConstant`
- 参数没写时，仍使用框架默认值
- 旧逻辑能继续跑通

### 4. 为日志与回放预留执行画像

每次 Agent 执行后，至少应该能拿到本次命中的：

- `promptKey`
- `promptVersion`
- `model`
- `temperature`
- `maxTokens`

这一层先不强求前端展示，但后端对象要先具备承载能力。

## 建议配置模型

### 1. 扩展 `PromptConfig`

建议从当前：

- `version`
- `templates`

升级为：

- `defaultVersion`
- `templates`
- `profiles`

其中：

- `templates`：保存 prompt 文本与版本
- `profiles`：保存各 Agent 的执行参数

建议结构示意：

```yaml
prompt:
  default-version: v1
  templates:
    agent1_title:
      version: v1
      content: ...
    style_tech:
      version: v1
      content: ...
  profiles:
    title-generator:
      prompt-key: agent1_title
      prompt-version: v1
      model: qwen-plus
      temperature: 0.7
      max-tokens: 2000
      top-p: 0.9
      structured-output: true
      streaming: false
```

### 2. 新增 `PromptTemplateDefinition`

建议新增简单模型，字段包括：

- `key`
- `version`
- `content`
- `description`

作用是让模板不再只是字符串。

### 3. 新增 `AgentProfile`

建议新增简单模型，字段包括：

- `name`
- `promptKey`
- `promptVersion`
- `model`
- `temperature`
- `maxTokens`
- `topP`
- `structuredOutput`
- `streaming`

它的职责就是表达“某个 Agent 默认该怎么跑”。

### 4. 新增 `AgentPromptContext`

建议新增轻量上下文对象，用来承载一次执行真正命中的配置：

- `agentName`
- `promptKey`
- `promptVersion`
- `model`
- `temperature`
- `maxTokens`

后面日志、节点回放、评测都可以直接复用它。

## 建议服务拆分

### 1. 新增 `PromptTemplateService`

职责：

- 按 key 读取模板
- 按 key + version 读取模板
- 模板不存在时自动回退到 `PromptConstant`

### 2. 新增 `AgentProfileService`

职责：

- 读取某个 Agent 的默认 profile
- 合并全局默认值与节点级覆盖值
- 生成可供执行链路记录的 `AgentPromptContext`

### 3. 暂不引入数据库

这一阶段先只做配置文件级能力，不做在线编辑后台。

原因很简单：

- 先把结构跑顺，比先做管理后台更重要
- 现在系统还在升级中，配置字段还会调整

## Agent 侧改造方式

本轮建议只改 4 个核心 Agent：

- `TitleGeneratorAgent`
- `OutlineGeneratorAgent`
- `ContentGeneratorAgent`
- `ImageAnalyzerAgent`

统一改造思路：

### 第一步：不再直接读 `PromptConstant`

改为：

- 通过 `PromptTemplateService` 取主模板
- 通过 `PromptTemplateService` 取 style prompt
- 如果缺失则由服务内部回退默认模板

### 第二步：统一解析 AgentProfile

每个 Agent 在执行时先拿到自己的 profile：

- 标题节点读取 `title-generator`
- 大纲节点读取 `outline-generator`
- 正文节点读取 `content-generator`
- 配图分析节点读取 `image-analyzer`

### 第三步：构造执行画像

在调用模型前构造 `AgentPromptContext`，供日志和后续回放使用。

### 第四步：模型参数先做到“可记录 + 可透传”

如果当前 `DashScopeChatModel` 接口支持在 Prompt 上附带 options，就把：

- model
- temperature
- maxTokens
- topP

真正透传给模型。

如果当前接入层暂时不方便传入全部参数，也至少先把这些字段配置化并记录下来，不阻塞第一步上线。

## 配置文件建议

### `application.yml`

建议新增：

- `prompt.default-version`
- `prompt.templates.*`
- `prompt.profiles.*`
- `article.agent.defaults.*`

其中 `article.agent.defaults` 可放全局默认参数，例如：

- 默认模型
- 默认 temperature
- 默认 maxTokens

这样 `AgentConfig` 就不再只有开关和迭代次数。

## 日志与兼容要求

### 本轮至少补齐的日志字段

建议后端节点执行日志新增或预留：

- `promptKey`
- `promptVersion`
- `model`
- `temperature`
- `maxTokens`

### 兼容要求

- 不删除 `PromptConstant`
- 不修改已有业务接口入参
- 不改变当前文章生成主流程
- 不影响 `ArticleAgentService` 旧模式继续运行

## 推荐改动文件

第一批建议改这些文件：

- `src/main/java/com/yupi/template/config/PromptConfig.java`
- `src/main/java/com/yupi/template/agent/config/AgentConfig.java`
- `src/main/java/com/yupi/template/agent/agents/TitleGeneratorAgent.java`
- `src/main/java/com/yupi/template/agent/agents/OutlineGeneratorAgent.java`
- `src/main/java/com/yupi/template/agent/agents/ContentGeneratorAgent.java`
- `src/main/java/com/yupi/template/agent/agents/ImageAnalyzerAgent.java`
- `src/main/resources/application.yml`

如有需要，再补：

- `src/main/java/com/yupi/template/config/PromptTemplateDefinition.java`
- `src/main/java/com/yupi/template/config/AgentProfile.java`
- `src/main/java/com/yupi/template/service/PromptTemplateService.java`
- `src/main/java/com/yupi/template/service/AgentProfileService.java`

## 验收标准

- 不改 Prompt 常量也能保持现有链路正常运行
- 修改 `application.yml` 后，可切换指定 Agent 的模板或参数
- 4 个核心 Agent 都不再直接依赖主 Prompt 常量
- 至少能在后端执行链路里拿到本次命中的 promptKey / promptVersion / model

## 当前结论

这一轮配置化不是“做配置而做配置”，而是为了给后面的节点回放、评测基线、工作流演进提供统一执行画像。先把 Prompt 和模型参数这层抽出来，后面整个 Agent 开发链路才会真正顺起来。
