# 第四阶段 Agent 开发能力升级方案

## 文档定位

这份文档用于承接第三阶段的记忆底座，继续把项目升级为“更适合持续开发 Agent”的系统。重点不再是页面小修小补，而是补齐四个真正影响后续研发效率的基础能力：

- Prompt / 模型 / 参数配置化
- 节点级回放 / 调试能力
- Agent 评测基线
- 工作流抽象层

## 为什么现在做

当前项目已经具备可运行的多 Agent 主链路，也有基础日志、节点观测、任务记忆与恢复能力，但这些能力仍然偏“能跑通”，还不够“能开发、能调试、能迭代”。

现在的主要瓶颈是：

- Prompt 仍大量写死在 `PromptConstant`
- 不同 Agent 缺少独立模型参数层，难以做 A/B 调整
- 节点日志能看结果，但还不能做真正的输入 / 输出回放
- 缺少固定评测样本，改完代码后无法稳定判断质量是否提升
- 工作流主要集中在 `ArticleAgentOrchestrator`，新增节点和分支时改动面较大

因此，这一阶段的目标不是“再多加几个 Agent”，而是让后续每次开发 Agent 能更快试错、更容易定位问题、更安全迭代。

## 现状基线

当前关键代码入口如下：

- 编排入口：`src/main/java/com/yupi/template/agent/ArticleAgentOrchestrator.java`
- 异步执行：`src/main/java/com/yupi/template/service/ArticleAsyncService.java`
- Prompt 常量：`src/main/java/com/yupi/template/constant/PromptConstant.java`
- Prompt 配置雏形：`src/main/java/com/yupi/template/config/PromptConfig.java`
- 执行观测：`src/main/java/com/yupi/template/aop/AgentExecutionAspect.java`
- 任务记忆：`src/main/java/com/yupi/template/service/impl/ArticleMemoryServiceImpl.java`
- 前端观测页：`frontend/src/pages/article/ArticleCreatePage.vue`

这些基础已经足够支撑升级，不需要推翻重做。

## 设计目标

### 1. Prompt / 模型 / 参数配置化

目标是把“写死在类里”的 Prompt 和模型参数收敛为可管理配置。

建议新增三层概念：

- `AgentProfile`：描述某个节点使用的模型、温度、最大输出长度、是否要求结构化输出
- `PromptTemplateDefinition`：描述 promptKey、版本号、模板内容、适用节点
- `AgentExecutionProfile`：一次任务实际命中的 promptVersion、model、temperature、maxTokens

建议做法：

- 保留 `PromptConstant` 作为默认兜底，不直接删除
- 扩展 `PromptConfig`，从“只存模板文本”升级为“模板 + 版本 + 参数默认值”
- 在标题、大纲、正文、图片分析这些 Agent 中统一改为通过配置服务取模板，而不是直接引用常量
- 在日志和节点快照中记录本次实际命中的 promptKey / promptVersion / model

这样后面调 Prompt 时，不需要每次都改 Java 逻辑。

### 2. 节点级回放 / 调试能力

目标是把当前“节点日志”升级为“节点调试资产”。

建议新增 `NodeReplaySnapshot` 概念，最少保存：

- 节点名、阶段、开始结束时间、耗时
- 输入摘要、输出摘要、错误摘要
- promptKey、promptVersion、model、temperature
- 结构化解析结果、重试次数、降级路径

后端上建议：

- 扩展 `AgentExecutionAspect`，不仅记录执行结果，也记录统一格式的节点输入输出摘要
- 在 `ArticleNodeLogServiceImpl` 或独立 `ArticleNodeReplayService` 中保存节点回放数据
- 在 `retryNode` 基础上继续演进，支持“读取最近一次节点快照后按当前配置重放”

前端上建议：

- 在 `ArticleCreatePage.vue` 和 `ArticleDetailPage.vue` 增加“节点时间线 / 调试抽屉”
- 支持查看某个节点的输入摘要、输出摘要、失败原因、重试历史、配置命中信息

这会直接提升排障效率，也是后面做单节点实验和回归验证的基础。

### 3. Agent 评测基线

目标是把“感觉变好了”变成“有基线地验证变好了”。

建议先做轻量评测，不上复杂平台，先在项目内建立：

- `EvalCase`：输入主题、风格、附加要求、期望特征
- `EvalRun`：一次评测运行记录
- `EvalScore`：标题质量、结构完整性、正文连贯性、配图相关性等维度分数

实施建议：

- 先准备一小批固定案例，覆盖科技、情感、教育、幽默等现有风格
- 提供本地命令或测试入口，批量跑标题 / 大纲 / 正文三个核心节点
- 评测结果先落 JSON 或 Redis / 本地文件，不急着上数据库
- 先做“规则评分 + LLM 评审评分”混合模式

这样每次改 Prompt、换模型、加节点后，都能做最小回归。

### 4. 工作流抽象层

目标是降低 `ArticleAgentOrchestrator` 的膨胀速度，让“节点”“边”“策略”可组合。

建议新增两层抽象：

- `WorkflowNodeDefinition`：节点标识、显示名、执行器、输入要求、输出类型
- `WorkflowPlanDefinition`：阶段内节点列表、边关系、条件分支、失败策略

对应思路：

- 保留现有 Spring AI Alibaba `StateGraph` 作为底层执行器
- 在上层增加一层项目自己的工作流定义，再由定义去构建 `StateGraph`
- 把“标题阶段”“大纲阶段”“正文阶段”从编排类中逐步拆成独立构建器

这样以后加 Planner、Reviewer、Tool Router 或多模型分支时，不需要一直把逻辑堆进一个 orchestrator 类。

## 推荐实施顺序

### 第一优先级：Prompt / 模型 / 参数配置化

这是所有 Agent 开发的最小底座。没有这层，后面做评测、回放、A/B 调整都会很重。

第一步建议主要改这些文件：

- `src/main/java/com/yupi/template/config/PromptConfig.java`
- `src/main/java/com/yupi/template/agent/config/AgentConfig.java`
- `src/main/java/com/yupi/template/agent/agents/TitleGeneratorAgent.java`
- `src/main/java/com/yupi/template/agent/agents/OutlineGeneratorAgent.java`
- `src/main/java/com/yupi/template/agent/agents/ContentGeneratorAgent.java`
- `src/main/java/com/yupi/template/agent/agents/ImageAnalyzerAgent.java`
- `src/main/resources/application.yml`

### 第二优先级：节点级回放 / 调试能力

这一步完成后，问题定位效率会明显提升，也能支撑单节点复现。

### 第三优先级：Agent 评测基线

等配置化与回放具备后，再建评测才有意义，否则评测结果无法解释。

### 第四优先级：工作流抽象层

这一步最偏重构，建议放在前三项稳定后推进，避免一开始改动面过大。

## 兼容策略

- 不删除现有 `PromptConstant`，先作为默认值和迁移期兜底
- 不破坏现有 `retryNode`、`resumeTask`、SSE 事件结构
- 不一次性改 Go / Python 版本，只在 Java 主后端落地
- 前端先增强调试面板，不重做创作页主流程

## 阶段结论

这一轮升级的本质，是把项目从“已经能跑的多 Agent Demo”继续推进为“适合长期打磨的 Agent 开发底座”。先把配置化、回放、评测、工作流抽象这四层打稳，后面再做 Planner、Reviewer、多模型路由、工具策略优化，成本会低很多，风险也更可控。
