# Agent 评测样本说明

## 目录说明

- `eval/cases/`：第一版固定评测样本集
- 每个样本对应一次标准化创作输入，用于后续批量回归执行

## 样本目标

第一版样本覆盖三类任务：

1. 标准创作任务
2. 边界任务
3. 回归任务

## 字段说明

- `caseId`：样本唯一标识
- `topic`：创作主题
- `style`：文章风格
- `userDescription`：附加创作要求
- `enabledImageMethods`：允许使用的图片方法
- `tags`：用于后续筛选和统计
- `expectations`：第一版轻量规则校验条件
- `notes`：样本设计意图，方便后续维护

## 当前样本列表

- `basic-tech-001.json`：标准科技主题
- `emotional-story-001.json`：情感叙事主题
- `education-topic-001.json`：教育讲解主题
- `diagram-architecture-001.json`：适合流程图 / 架构图主题
- `regression-image-fallback-001.json`：图片降级回归主题
- `ambiguous-brief-001.json`：描述模糊边界主题

## 下一步

样本集落地后，下一步直接接本地评测执行入口，支持批量读取这些 case 并输出 `summary.json`。

## 本地执行方式

当前已接入第一版本地评测 Runner，默认关闭。

如需执行评测，可临时开启配置：

```yaml
article:
  eval:
    enabled: true
    user-account: admin
```

启动应用后，Runner 会自动：

1. 读取 `eval/cases/*.json`
2. 创建评测任务
3. 自动确认标题与大纲
4. 等待内容链路完成
5. 收集快照、执行日志、重放数据、记忆上下文
6. 输出到 `eval/runs/{runId}/`

输出结构示例：

```text
eval/runs/
  2026-05-25-210000/
    summary.json
    cases/
      basic-tech-001.json
      diagram-architecture-001.json
```
