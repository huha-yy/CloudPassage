<template>
  <div class="task-memory-panel">
    <div class="panel-header">
      <div class="panel-heading">
        <div class="panel-title-row">
          <DatabaseOutlined class="panel-icon" />
          <span class="panel-title">{{ title }}</span>
        </div>
        <p class="panel-subtitle">{{ subtitle }}</p>
      </div>
      <a-button
        size="small"
        type="text"
        :loading="loading"
        class="refresh-btn"
        @click="loadMemory(false)"
      >
        <template #icon>
          <ReloadOutlined />
        </template>
        刷新
      </a-button>
    </div>

    <a-spin :spinning="loading">
      <div v-if="memory" class="panel-body">
        <div class="summary-grid">
          <div class="summary-card">
            <span class="summary-label">当前阶段</span>
            <span class="summary-value">{{ getPhaseText(memory.currentPhase) }}</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">重试次数</span>
            <span class="summary-value">{{ memory.retryCount ?? 0 }}</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">最近成功节点</span>
            <span class="summary-value">{{ getNodeText(memory.lastSuccessNode) }}</span>
          </div>
          <div class="summary-card danger">
            <span class="summary-label">最近失败节点</span>
            <span class="summary-value">{{ getNodeText(memory.lastFailedNode) }}</span>
          </div>
        </div>

        <div class="memory-group">
          <div class="group-title">
            <BranchesOutlined />
            <span>任务上下文</span>
          </div>
          <div class="kv-list">
            <div class="kv-item">
              <span class="kv-key">主题</span>
              <span class="kv-value">{{ memory.topic || '--' }}</span>
            </div>
            <div class="kv-item">
              <span class="kv-key">风格</span>
              <span class="kv-value">{{ memory.style || '--' }}</span>
            </div>
            <div class="kv-item">
              <span class="kv-key">补充描述</span>
              <span class="kv-value multiline">{{ memory.userDescription || '--' }}</span>
            </div>
          </div>
        </div>

        <div class="memory-group">
          <div class="group-title">
            <PictureOutlined />
            <span>图片策略</span>
          </div>
          <div class="summary-grid summary-grid--strategy">
            <div class="summary-card muted">
              <span class="summary-label">启用方法</span>
              <span class="summary-value">{{ joinValues(memory.imageStrategy?.methods) }}</span>
            </div>
            <div class="summary-card muted">
              <span class="summary-label">图片来源</span>
              <span class="summary-value">{{ joinValues(memory.imageStrategy?.sources) }}</span>
            </div>
            <div class="summary-card muted">
              <span class="summary-label">需求数量</span>
              <span class="summary-value">{{ memory.imageStrategy?.requirementCount ?? 0 }}</span>
            </div>
            <div class="summary-card muted">
              <span class="summary-label">已生成数量</span>
              <span class="summary-value">{{ memory.imageStrategy?.generatedCount ?? 0 }}</span>
            </div>
          </div>
        </div>

        <div class="memory-group">
          <div class="group-title">
            <CheckCircleOutlined />
            <span>已沉淀结果</span>
          </div>
          <div class="kv-list">
            <div class="kv-item">
              <span class="kv-key">选定标题</span>
              <span class="kv-value multiline">{{ selectedTitleText }}</span>
            </div>
          </div>

          <div class="summary-block">
            <div class="summary-block-title">大纲摘要</div>
            <div class="summary-text">{{ memory.outlineSummary?.text || '--' }}</div>
            <div class="meta-line">
              <span>来源：{{ memory.outlineSummary?.sourceType || '--' }}</span>
              <span>章节数：{{ memory.outlineSummary?.sourceCount ?? 0 }}</span>
            </div>
            <div v-if="memory.outlineSummary?.highlights?.length" class="chip-list">
              <a-tag v-for="item in memory.outlineSummary.highlights" :key="item" color="cyan">
                {{ item }}
              </a-tag>
            </div>
          </div>

          <div class="summary-block">
            <div class="summary-block-title">正文摘要</div>
            <div class="summary-text">{{ memory.contentSummary?.text || '--' }}</div>
            <div class="meta-line">
              <span>来源：{{ memory.contentSummary?.sourceType || '--' }}</span>
              <span>内容长度：{{ memory.contentSummary?.sourceCount ?? 0 }}</span>
            </div>
            <div v-if="memory.contentSummary?.highlights?.length" class="chip-list">
              <a-tag v-for="item in memory.contentSummary.highlights" :key="item" color="geekblue">
                {{ item }}
              </a-tag>
            </div>
          </div>
        </div>

        <div class="memory-group">
          <div class="group-title">
            <HistoryOutlined />
            <span>人工动作</span>
          </div>
          <div v-if="memory.manualActions?.length" class="event-list">
            <div v-for="(item, index) in memory.manualActions" :key="`${item.type || 'action'}-${index}`" class="event-item action">
              <div class="event-top">
                <span class="event-label">{{ item.label || item.type || '--' }}</span>
                <span class="event-time">{{ formatTimestamp(item.timestamp) }}</span>
              </div>
              <div class="meta-line">
                <span>阶段：{{ getPhaseText(item.phase) }}</span>
                <span>节点：{{ getNodeText(item.node) }}</span>
              </div>
              <div v-if="item.detail" class="event-detail">{{ item.detail }}</div>
            </div>
          </div>
          <div v-else class="empty-text">暂无人工动作</div>
        </div>

        <div class="memory-group">
          <div class="group-title">
            <RadarChartOutlined />
            <span>质量信号</span>
          </div>
          <div v-if="memory.qualitySignals?.length" class="event-list">
            <div v-for="(item, index) in memory.qualitySignals" :key="`${item.code || 'signal'}-${index}`" class="event-item signal">
              <div class="event-top">
                <span class="event-label">{{ item.label || item.code || '--' }}</span>
                <span class="event-time">{{ formatTimestamp(item.timestamp) }}</span>
              </div>
              <div class="meta-line">
                <span>阶段：{{ getPhaseText(item.phase) }}</span>
                <span>节点：{{ getNodeText(item.node) }}</span>
              </div>
              <div v-if="item.detail" class="event-detail">{{ item.detail }}</div>
            </div>
          </div>
          <div v-else class="empty-text">暂无质量信号</div>
        </div>

        <div class="memory-group">
          <div class="group-title">
            <DeploymentUnitOutlined />
            <span>节点快照</span>
          </div>
          <div v-if="memory.nodeSnapshots?.length" class="event-list">
            <div
              v-for="(item, index) in memory.nodeSnapshots"
              :key="`${item.node || 'snapshot'}-${index}`"
              class="event-item snapshot"
            >
              <div class="event-top">
                <div class="snapshot-heading">
                  <span class="event-label">{{ item.label || getNodeText(item.node) }}</span>
                  <a-tag :color="getSnapshotStatusColor(item.status)">{{ item.status || '--' }}</a-tag>
                </div>
                <span class="event-time">{{ formatTimestamp(item.timestamp) }}</span>
              </div>
              <div class="meta-line">
                <span>阶段：{{ getPhaseText(item.phase) }}</span>
                <span>节点：{{ getNodeText(item.node) }}</span>
              </div>
              <div v-if="item.summary" class="event-detail">{{ item.summary }}</div>
              <div v-if="item.detail" class="event-detail secondary">{{ item.detail }}</div>
              <div v-if="item.highlights?.length" class="chip-list">
                <a-tag v-for="highlight in item.highlights" :key="highlight" color="gold">
                  {{ highlight }}
                </a-tag>
              </div>
            </div>
          </div>
          <div v-else class="empty-text">暂无节点快照</div>
        </div>

        <div v-if="memory.recentErrorMessage" class="memory-group">
          <div class="group-title error">
            <CloseCircleOutlined />
            <span>最近错误</span>
          </div>
          <div class="error-box">{{ memory.recentErrorMessage }}</div>
        </div>

        <div class="panel-footer">
          <span class="footer-item">任务 ID：{{ memory.taskId || taskId || '--' }}</span>
          <span class="footer-item">更新时间：{{ formatTimestamp(memory.updatedAt) }}</span>
        </div>
      </div>

      <a-empty
        v-else
        :image="false"
        description="当前还没有可展示的任务记忆"
        class="empty-state"
      />
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  BranchesOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  HistoryOutlined,
  PictureOutlined,
  RadarChartOutlined,
  ReloadOutlined,
} from '@ant-design/icons-vue'
import { getTaskMemory } from '@/api/articleController'

interface Props {
  taskId?: string
  title?: string
  subtitle?: string
  autoRefresh?: boolean
  refreshInterval?: number
}

const props = withDefaults(defineProps<Props>(), {
  taskId: '',
  title: '任务记忆面板',
  subtitle: '展示当前任务沉淀的上下文、摘要、动作与失败线索',
  autoRefresh: false,
  refreshInterval: 3000,
})

const loading = ref(false)
const memory = ref<API.ArticleTaskMemoryVO | null>(null)
let refreshTimer: ReturnType<typeof setInterval> | null = null

const selectedTitleText = computed(() => {
  if (!memory.value?.selectedTitle?.mainTitle && !memory.value?.selectedTitle?.subTitle) {
    return '--'
  }
  return [memory.value.selectedTitle?.mainTitle, memory.value.selectedTitle?.subTitle]
    .filter(Boolean)
    .join(' / ')
})

const loadMemory = async (silent = true) => {
  if (!props.taskId) {
    memory.value = null
    return
  }

  if (!silent) {
    loading.value = true
  }
  try {
    const res = await getTaskMemory({ taskId: props.taskId })
    memory.value = res.data.data || null
  } catch (error) {
    if (!silent) {
      console.error('Failed to load task memory:', error)
    }
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

const stopRefresh = () => {
  if (refreshTimer !== null) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

const startRefresh = () => {
  stopRefresh()
  if (!props.autoRefresh || !props.taskId) {
    return
  }
  refreshTimer = setInterval(() => {
    void loadMemory(true)
  }, props.refreshInterval)
}

const getPhaseText = (phase?: string) => {
  const phaseMap: Record<string, string> = {
    PENDING: '等待中',
    TITLE_GENERATING: '标题生成',
    TITLE_SELECTING: '标题选择',
    OUTLINE_GENERATING: '大纲生成',
    OUTLINE_EDITING: '大纲编辑',
    CONTENT_GENERATING: '正文生成',
    COMPLETED: '已完成',
  }
  return phaseMap[phase || ''] || phase || '--'
}

const getNodeText = (node?: string) => {
  const nodeMap: Record<string, string> = {
    workflow_phase_1: '工作流阶段一',
    workflow_phase_2: '工作流阶段二',
    workflow_phase_3: '工作流阶段三',
    agent1_generate_titles: '标题生成',
    agent2_generate_outline: '大纲生成',
    ai_modify_outline: 'AI 修改大纲',
    agent3_generate_content: '正文生成',
    agent4_analyze_image_requirements: '图片需求分析',
    agent5_generate_images: '图片生成',
    agent6_merge_content: '图文合并',
    workflow_error: '工作流异常',
  }
  return nodeMap[node || ''] || node || '--'
}

const joinValues = (values?: string[]) => {
  return values && values.length > 0 ? values.join(' / ') : '--'
}

const formatTimestamp = (value?: number) => {
  if (!value) {
    return '--'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

const getSnapshotStatusColor = (status?: string) => {
  switch (status) {
    case 'SUCCESS':
      return 'green'
    case 'FAILED':
      return 'red'
    case 'RUNNING':
      return 'processing'
    default:
      return 'default'
  }
}

watch(
  () => props.taskId,
  (taskId) => {
    if (!taskId) {
      memory.value = null
      stopRefresh()
      return
    }
    void loadMemory(false)
    startRefresh()
  },
  { immediate: true },
)

watch(
  () => [props.autoRefresh, props.refreshInterval] as const,
  () => {
    startRefresh()
  },
)

onBeforeUnmount(() => {
  stopRefresh()
})
</script>

<style scoped lang="scss">
.task-memory-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.panel-heading {
  min-width: 0;
}

.panel-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.panel-icon {
  color: #0f766e;
  font-size: 16px;
}

.panel-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--color-text);
}

.panel-subtitle {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text-muted);
}

.refresh-btn {
  flex-shrink: 0;
}

.panel-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;

  &.summary-grid--strategy {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.summary-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(15, 118, 110, 0.08) 0%, rgba(15, 118, 110, 0.03) 100%);
  border: 1px solid rgba(15, 118, 110, 0.12);

  &.danger {
    background: linear-gradient(180deg, rgba(239, 68, 68, 0.08) 0%, rgba(239, 68, 68, 0.03) 100%);
    border-color: rgba(239, 68, 68, 0.14);
  }

  &.muted {
    background: linear-gradient(180deg, rgba(15, 23, 42, 0.04) 0%, rgba(15, 23, 42, 0.02) 100%);
    border-color: rgba(15, 23, 42, 0.08);
  }
}

.summary-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

.summary-value {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.5;
  color: var(--color-text);
  word-break: break-word;
}

.memory-group {
  padding: 14px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid var(--color-border);
}

.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text);

  &.error {
    color: #dc2626;
  }
}

.kv-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.kv-item {
  display: grid;
  grid-template-columns: 78px 1fr;
  gap: 10px;
  align-items: start;
}

.kv-key {
  font-size: 12px;
  color: var(--color-text-muted);
}

.kv-value {
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text);
  word-break: break-word;

  &.multiline {
    white-space: pre-wrap;
  }
}

.summary-block {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 14px;
  background: var(--color-background-secondary);
  border: 1px solid var(--color-border-light);
}

.summary-block-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: 8px;
}

.summary-text {
  font-size: 13px;
  line-height: 1.8;
  color: var(--color-text);
  white-space: pre-wrap;
}

.meta-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--color-text-muted);
}

.chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.event-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.event-item {
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px solid var(--color-border-light);

  &.action {
    background: rgba(34, 197, 94, 0.04);
  }

  &.signal {
    background: rgba(59, 130, 246, 0.04);
  }

  &.snapshot {
    background: rgba(245, 158, 11, 0.06);
  }
}

.event-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.event-label {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text);
}

.event-time {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--color-text-muted);
}

.snapshot-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.event-detail {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text-secondary);
  white-space: pre-wrap;

  &.secondary {
    color: var(--color-text-muted);
  }
}

.empty-text {
  font-size: 12px;
  color: var(--color-text-muted);
}

.error-box {
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(239, 68, 68, 0.08);
  border: 1px solid rgba(239, 68, 68, 0.14);
  color: #b91c1c;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.panel-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  padding-top: 4px;
}

.footer-item {
  font-size: 12px;
  color: var(--color-text-muted);
}

.empty-state {
  padding: 24px 0;
}

@media (max-width: 768px) {
  .summary-grid,
  .summary-grid.summary-grid--strategy {
    grid-template-columns: 1fr;
  }

  .kv-item {
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .panel-header,
  .event-top {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
