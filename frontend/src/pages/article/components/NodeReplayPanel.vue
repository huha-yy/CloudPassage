<template>
  <div class="node-replay-panel">
    <div class="panel-header">
      <div class="panel-heading">
        <div class="panel-title-row">
          <BugOutlined class="panel-icon" />
          <span class="panel-title">{{ title }}</span>
        </div>
        <p class="panel-subtitle">{{ subtitle }}</p>
      </div>
      <a-button
        size="small"
        type="text"
        :loading="loading"
        class="refresh-btn"
        @click="loadSnapshots(false)"
      >
        <template #icon>
          <ReloadOutlined />
        </template>
        刷新
      </a-button>
    </div>

    <a-spin :spinning="loading">
      <div v-if="snapshots.length > 0" class="panel-body">
        <div class="summary-grid">
          <div class="summary-card">
            <span class="summary-label">总快照数</span>
            <span class="summary-value">{{ snapshots.length }}</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">可回放节点</span>
            <span class="summary-value">{{ replayableCount }}</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">失败节点</span>
            <span class="summary-value">{{ failedCount }}</span>
          </div>
          <div class="summary-card muted">
            <span class="summary-label">最近更新</span>
            <span class="summary-value">{{ formatTimestamp(latestTimestamp) }}</span>
          </div>
        </div>

        <div class="filter-bar">
          <div class="filter-heading">
            <FilterOutlined />
            <span>筛选调试快照</span>
          </div>
          <div class="filter-controls">
            <a-select
              v-model:value="statusFilter"
              class="filter-select"
              :options="statusOptions"
            />
            <a-select
              v-model:value="nodeFilter"
              class="filter-select filter-select-wide"
              :options="nodeOptions"
              show-search
              option-filter-prop="label"
            />
            <a-input
              v-model:value="keyword"
              class="filter-input"
              allow-clear
              placeholder="搜索节点 / 模型 / Prompt / 错误"
            >
              <template #prefix>
                <SearchOutlined />
              </template>
            </a-input>
            <a-button size="small" @click="resetFilters">重置</a-button>
          </div>
          <div class="filter-summary">
            当前显示 {{ filteredSnapshots.length }} / {{ snapshots.length }} 条节点快照
          </div>
        </div>

        <div v-if="filteredSnapshots.length > 0" class="timeline">
          <div
            v-for="(item, index) in filteredSnapshots"
            :key="item.snapshotId || `${item.node || 'node'}-${index}`"
            :class="['timeline-item', resolveStatusClass(item.status)]"
          >
            <div class="timeline-indicator">
              <CheckCircleOutlined v-if="item.status === 'SUCCESS'" class="icon success" />
              <CloseCircleOutlined v-else-if="item.status === 'FAILED'" class="icon failed" />
              <LoadingOutlined v-else class="icon running" />
            </div>

            <div class="timeline-content">
              <div class="timeline-top">
                <div class="timeline-heading">
                  <span class="timeline-title">{{ getNodeText(item.node) }}</span>
                  <a-tag :color="getStatusColor(item.status)">{{ getStatusText(item.status) }}</a-tag>
                  <a-tag v-if="item.replayable" color="gold">可回放</a-tag>
                </div>
                <span class="timeline-duration">{{ item.elapsedMs ?? 0 }}ms</span>
              </div>

              <div class="meta-line">
                <span>阶段：{{ getPhaseText(item.phase) }}</span>
                <span>开始：{{ formatTimestamp(item.startedAt) }}</span>
                <span>结束：{{ formatTimestamp(item.finishedAt) }}</span>
                <span>重试序号：{{ item.retryCount ?? 0 }}</span>
              </div>

              <div v-if="hasConfig(item)" class="config-row">
                <span v-if="item.model" class="config-chip">{{ item.model }}</span>
                <span v-if="item.promptKey" class="config-chip">
                  {{ item.promptKey }}@{{ item.promptVersion || 'default' }}
                </span>
                <span
                  v-if="item.temperature !== undefined && item.temperature !== null"
                  class="config-chip"
                >
                  Temp {{ formatConfigNumber(item.temperature) }}
                </span>
                <span v-if="item.maxTokens" class="config-chip">Max {{ item.maxTokens }}</span>
                <span v-if="item.topP !== undefined && item.topP !== null" class="config-chip">
                  TopP {{ formatConfigNumber(item.topP) }}
                </span>
              </div>

              <div v-if="item.message" class="message-block">
                <div class="block-label">执行说明</div>
                <div class="block-content">{{ item.message }}</div>
              </div>

              <div v-if="item.inputSummary" class="message-block">
                <div class="block-label">输入快照</div>
                <div class="block-content code-like">{{ item.inputSummary }}</div>
              </div>

              <div v-if="item.outputSummary" class="message-block">
                <div class="block-label">输出快照</div>
                <div class="block-content code-like">{{ item.outputSummary }}</div>
              </div>

              <div v-if="item.errorMessage" class="message-block error">
                <div class="block-label">错误信息</div>
                <div class="block-content">{{ item.errorMessage }}</div>
              </div>

              <div v-if="showRetryAction && item.replayable && item.node" class="action-row">
                <a-button type="link" size="small" class="retry-btn" @click="emitRetry(item.node)">
                  <template #icon>
                    <RedoOutlined />
                  </template>
                  重试该节点
                </a-button>
              </div>
            </div>
          </div>
        </div>

        <a-empty
          v-else
          :image="false"
          description="当前筛选条件下没有匹配的节点快照"
          class="empty-state"
        />
      </div>

      <a-empty
        v-else
        :image="false"
        description="当前还没有可展示的节点调试快照"
        class="empty-state"
      />
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  BugOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  FilterOutlined,
  LoadingOutlined,
  RedoOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons-vue'
import { getNodeReplaySnapshots } from '@/api/articleController'

interface Props {
  taskId?: string
  title?: string
  subtitle?: string
  autoRefresh?: boolean
  refreshInterval?: number
  showRetryAction?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  taskId: '',
  title: '节点调试面板',
  subtitle: '查看每个 Agent 节点的输入、输出、耗时、模型配置与失败原因',
  autoRefresh: false,
  refreshInterval: 3000,
  showRetryAction: false,
})

const emit = defineEmits<{
  (e: 'retry-node', node: string): void
}>()

type StatusFilter = 'ALL' | 'FAILED' | 'REPLAYABLE' | 'RUNNING' | 'SUCCESS'

const loading = ref(false)
const snapshots = ref<API.NodeReplaySnapshotVO[]>([])
const statusFilter = ref<StatusFilter>('ALL')
const nodeFilter = ref<string>('ALL')
const keyword = ref('')
let refreshTimer: ReturnType<typeof setInterval> | null = null

const statusOptions = [
  { label: '全部状态', value: 'ALL' },
  { label: '仅失败节点', value: 'FAILED' },
  { label: '仅可回放', value: 'REPLAYABLE' },
  { label: '执行中', value: 'RUNNING' },
  { label: '成功节点', value: 'SUCCESS' },
]

const nodeOptions = computed(() => {
  const options = [
    { label: '全部节点', value: 'ALL' },
  ]
  const uniqueNodes = Array.from(new Set(
    snapshots.value
      .map(item => item.node)
      .filter((value): value is string => !!value),
  ))
  uniqueNodes.forEach(node => {
    options.push({
      label: getNodeText(node),
      value: node,
    })
  })
  return options
})

const sortedSnapshots = computed(() => {
  return [...snapshots.value].sort((a, b) => (b.startedAt ?? 0) - (a.startedAt ?? 0))
})

const filteredSnapshots = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLowerCase()
  return sortedSnapshots.value.filter(item => {
    if (statusFilter.value === 'FAILED' && item.status !== 'FAILED') {
      return false
    }
    if (statusFilter.value === 'REPLAYABLE' && !item.replayable) {
      return false
    }
    if (statusFilter.value === 'RUNNING' && item.status !== 'RUNNING') {
      return false
    }
    if (statusFilter.value === 'SUCCESS' && item.status !== 'SUCCESS') {
      return false
    }
    if (nodeFilter.value !== 'ALL' && item.node !== nodeFilter.value) {
      return false
    }
    if (!normalizedKeyword) {
      return true
    }

    const searchableText = [
      item.node,
      getNodeText(item.node),
      item.model,
      item.promptKey,
      item.promptVersion,
      item.message,
      item.errorMessage,
      item.inputSummary,
      item.outputSummary,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()

    return searchableText.includes(normalizedKeyword)
  })
})

const replayableCount = computed(() => {
  return snapshots.value.filter(item => item.replayable).length
})

const failedCount = computed(() => {
  return snapshots.value.filter(item => item.status === 'FAILED').length
})

const latestTimestamp = computed(() => {
  return snapshots.value.reduce((latest, item) => {
    const current = item.finishedAt ?? item.startedAt ?? 0
    return current > latest ? current : latest
  }, 0)
})

const loadSnapshots = async (silent = true) => {
  if (!props.taskId) {
    snapshots.value = []
    return
  }

  if (!silent) {
    loading.value = true
  }
  try {
    const res = await getNodeReplaySnapshots({ taskId: props.taskId })
    snapshots.value = res.data.data || []
  } catch (error) {
    if (!silent) {
      console.error('Failed to load node replay snapshots:', error)
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
    void loadSnapshots(true)
  }, props.refreshInterval)
}

const resetFilters = () => {
  statusFilter.value = 'ALL'
  nodeFilter.value = 'ALL'
  keyword.value = ''
}

const emitRetry = (node: string) => {
  emit('retry-node', node)
}

const resolveStatusClass = (status?: string) => {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'failed'
    default:
      return 'running'
  }
}

const getStatusColor = (status?: string) => {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'error'
    case 'RUNNING':
      return 'processing'
    default:
      return 'default'
  }
}

const getStatusText = (status?: string) => {
  const textMap: Record<string, string> = {
    RUNNING: '执行中',
    SUCCESS: '成功',
    FAILED: '失败',
  }
  return textMap[status || ''] || status || '--'
}

const getPhaseText = (phase?: string) => {
  const phaseMap: Record<string, string> = {
    PENDING: '等待中',
    TITLE_GENERATING: '标题生成',
    TITLE_SELECTING: '标题确认',
    OUTLINE_GENERATING: '大纲生成',
    OUTLINE_EDITING: '大纲调整',
    CONTENT_GENERATING: '正文生成',
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
    agent4_analyze_image_requirements: '分析配图需求',
    agent5_generate_images: '图片生成',
    agent6_merge_content: '图文合成',
    workflow_error: '工作流异常',
  }
  return nodeMap[node || ''] || node || '--'
}

const formatTimestamp = (value?: number) => {
  if (!value) {
    return '--'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

const formatConfigNumber = (value?: number | null) => {
  if (value === undefined || value === null || Number.isNaN(Number(value))) {
    return '--'
  }
  return Number(value).toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')
}

const hasConfig = (item: API.NodeReplaySnapshotVO) => {
  return !!(
    item.model ||
    item.promptKey ||
    item.temperature !== undefined ||
    item.maxTokens !== undefined ||
    item.topP !== undefined
  )
}

watch(
  () => props.taskId,
  (taskId) => {
    if (!taskId) {
      snapshots.value = []
      stopRefresh()
      return
    }
    void loadSnapshots(false)
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
.node-replay-panel {
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
  color: #7c3aed;
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
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.summary-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(124, 58, 237, 0.08) 0%, rgba(124, 58, 237, 0.03) 100%);
  border: 1px solid rgba(124, 58, 237, 0.12);

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
  color: var(--color-text);
  word-break: break-word;
}

.filter-bar {
  padding: 14px;
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(124, 58, 237, 0.05) 0%, rgba(124, 58, 237, 0.02) 100%);
  border: 1px solid rgba(124, 58, 237, 0.12);
}

.filter-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text);
}

.filter-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.filter-select {
  width: 140px;
}

.filter-select-wide {
  width: 180px;
}

.filter-input {
  width: 260px;
}

.filter-summary {
  margin-top: 10px;
  font-size: 12px;
  color: var(--color-text-muted);
}

.timeline {
  position: relative;

  &::before {
    content: '';
    position: absolute;
    left: 16px;
    top: 10px;
    bottom: 10px;
    width: 2px;
    background: var(--color-border-light);
  }
}

.timeline-item {
  position: relative;
  padding-left: 46px;
  padding-bottom: 16px;

  &:last-child {
    padding-bottom: 0;
  }
}

.timeline-indicator {
  position: absolute;
  left: 8px;
  top: 4px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: white;
  border: 2px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: center;

  .icon {
    font-size: 11px;

    &.success {
      color: var(--color-success);
    }

    &.failed {
      color: var(--color-error);
    }

    &.running {
      color: var(--color-primary);
    }
  }
}

.timeline-item.success .timeline-indicator {
  border-color: var(--color-success);
}

.timeline-item.failed .timeline-indicator {
  border-color: var(--color-error);
}

.timeline-item.running .timeline-indicator {
  border-color: var(--color-primary);
}

.timeline-content {
  padding: 14px;
  border-radius: 16px;
  background: white;
  border: 1px solid var(--color-border);
}

.timeline-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.timeline-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.timeline-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--color-text);
}

.timeline-duration {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 700;
  color: var(--color-primary);
}

.meta-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--color-text-muted);
}

.config-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.config-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.05);
  border: 1px solid rgba(148, 163, 184, 0.24);
  color: var(--color-text-secondary);
  font-size: 11px;
}

.message-block {
  margin-top: 12px;
  padding: 12px;
  border-radius: 14px;
  background: var(--color-background-secondary);
  border: 1px solid var(--color-border-light);

  &.error {
    background: rgba(239, 68, 68, 0.06);
    border-color: rgba(239, 68, 68, 0.14);
  }
}

.block-label {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--color-text);
}

.block-content {
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;

  &.code-like {
    font-family: 'Consolas', 'Courier New', monospace;
    font-size: 12px;
  }
}

.action-row {
  margin-top: 10px;
}

.retry-btn.ant-btn-link {
  padding: 0;
  height: auto;
  color: #d48806;
  font-size: 12px;
  font-weight: 600;
}

.empty-state {
  padding: 24px 0;
}

@media (max-width: 768px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .filter-controls {
    flex-direction: column;
  }

  .filter-select,
  .filter-select-wide,
  .filter-input {
    width: 100%;
  }

  .panel-header,
  .timeline-top {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
