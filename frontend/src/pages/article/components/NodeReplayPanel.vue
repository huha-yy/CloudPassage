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
            <a-segmented
              v-model:value="viewMode"
              class="view-mode-switch"
              :options="viewModeOptions"
            />
            <a-button size="small" @click="resetFilters">重置</a-button>
          </div>
          <div class="filter-summary">
            当前显示 {{ filteredSnapshots.length }} / {{ snapshots.length }} 条节点快照
          </div>
        </div>

        <div class="inspector-grid">
          <div class="inspector-summary">
            <div class="summary-head">
              <span class="summary-title">调试摘要</span>
              <span class="summary-subtitle">聚合当前筛选结果中的异常、慢节点与最近执行</span>
            </div>
            <div class="summary-cards">
              <div class="mini-card">
                <span class="mini-label">最新节点</span>
                <span class="mini-value">{{ getNodeText(latestSnapshot?.node) }}</span>
              </div>
              <div class="mini-card">
                <span class="mini-label">最慢节点</span>
                <span class="mini-value">{{ getNodeText(slowestSnapshot?.node) }}</span>
              </div>
              <div class="mini-card">
                <span class="mini-label">最近失败</span>
                <span class="mini-value">{{ getNodeText(latestFailedSnapshot?.node) }}</span>
              </div>
            </div>
            <div v-if="selectedSnapshot" class="summary-tip">
              当前选中：{{ getNodeText(selectedSnapshot.node) }}，可在右侧查看完整输入输出与决策详情
            </div>

            <div class="summary-section">
              <div class="summary-section-title">失败原因聚合</div>
              <div v-if="failureReasonItems.length > 0" class="reason-list">
                <div
                  v-for="item in failureReasonItems"
                  :key="item.label"
                  class="reason-item"
                >
                  <span class="reason-label">{{ item.label }}</span>
                  <a-tag color="error">{{ item.count }}</a-tag>
                </div>
              </div>
              <div v-else class="summary-empty">当前筛选结果中没有失败原因聚合项</div>
            </div>
          </div>

          <div class="inspector-detail">
            <div class="summary-head">
              <span class="summary-title">节点详情检查器</span>
              <span class="summary-subtitle">点击左侧时间线卡片切换检查对象</span>
            </div>
            <template v-if="selectedSnapshot">
              <div class="detail-top">
                <div class="detail-heading">
                  <span class="detail-title">{{ getNodeText(selectedSnapshot.node) }}</span>
                  <a-tag :color="getStatusColor(selectedSnapshot.status)">
                    {{ getStatusText(selectedSnapshot.status) }}
                  </a-tag>
                  <a-tag v-if="selectedSnapshot.replayable" color="gold">可回放</a-tag>
                </div>
                <span class="detail-duration">{{ selectedSnapshot.elapsedMs ?? 0 }}ms</span>
              </div>

              <div class="detail-meta">
                <span>阶段：{{ getPhaseText(selectedSnapshot.phase) }}</span>
                <span>开始：{{ formatTimestamp(selectedSnapshot.startedAt) }}</span>
                <span>结束：{{ formatTimestamp(selectedSnapshot.finishedAt) }}</span>
                <span>重试序号：{{ selectedSnapshot.retryCount ?? 0 }}</span>
              </div>

              <div v-if="hasConfig(selectedSnapshot)" class="config-row">
                <span v-if="selectedSnapshot.model" class="config-chip">{{ selectedSnapshot.model }}</span>
                <span v-if="selectedSnapshot.promptKey" class="config-chip">
                  {{ selectedSnapshot.promptKey }}@{{ selectedSnapshot.promptVersion || 'default' }}
                </span>
                <span
                  v-if="selectedSnapshot.temperature !== undefined && selectedSnapshot.temperature !== null"
                  class="config-chip"
                >
                  Temp {{ formatConfigNumber(selectedSnapshot.temperature) }}
                </span>
                <span v-if="selectedSnapshot.maxTokens" class="config-chip">
                  Max {{ selectedSnapshot.maxTokens }}
                </span>
                <span
                  v-if="selectedSnapshot.topP !== undefined && selectedSnapshot.topP !== null"
                  class="config-chip"
                >
                  TopP {{ formatConfigNumber(selectedSnapshot.topP) }}
                </span>
              </div>

              <div class="detail-sections">
                <div
                  v-if="selectedSnapshot.decisionSummary || selectedSnapshot.decisionSource || selectedSnapshot.decisionReason"
                  class="message-block"
                >
                  <div class="block-label">决策信息</div>
                  <div v-if="selectedSnapshot.decisionSummary" class="block-content">
                    {{ selectedSnapshot.decisionSummary }}
                  </div>
                  <div v-if="selectedSnapshot.decisionSource || selectedSnapshot.decisionReason" class="meta-line">
                    <span v-if="selectedSnapshot.decisionSource">
                      来源：{{ getDecisionSourceText(selectedSnapshot.decisionSource) }}
                    </span>
                    <span v-if="selectedSnapshot.decisionReason">
                      原因：{{ formatDecisionReason(selectedSnapshot.decisionReason) }}
                    </span>
                  </div>
                </div>

                <div
                  v-if="selectedSnapshot.memoryContextSummary || selectedSnapshot.memoryContextSnapshot"
                  class="message-block"
                >
                  <div class="block-top">
                    <div class="block-label">记忆上下文</div>
                    <a-button
                      v-if="selectedSnapshot.memoryContextSnapshot"
                      type="link"
                      size="small"
                      @click="toggleSection('memory')"
                    >
                      {{ isSectionExpanded('memory') ? '收起快照' : '展开快照' }}
                    </a-button>
                  </div>
                  <div v-if="selectedSnapshot.memoryContextSummary" class="block-content">
                    {{ selectedSnapshot.memoryContextSummary }}
                  </div>
                  <div
                    v-if="selectedSnapshot.memoryContextSnapshot"
                    class="block-content code-like"
                  >
                    {{ renderBlockContent(formatMemoryContextSnapshot(selectedSnapshot.memoryContextSnapshot), 'memory', 320) }}
                  </div>
                </div>

                <div
                  v-if="selectedSnapshot.fallbackSummary || selectedSnapshot.fallbackSource || selectedSnapshot.fallbackReason"
                  class="message-block"
                >
                  <div class="block-label">降级信息</div>
                  <div v-if="selectedSnapshot.fallbackSummary" class="block-content">
                    {{ formatFallbackSummary(selectedSnapshot.fallbackSummary) }}
                  </div>
                  <div v-if="selectedSnapshot.fallbackSource || selectedSnapshot.fallbackReason" class="meta-line">
                    <span v-if="selectedSnapshot.fallbackSource">
                      来源：{{ getFallbackSourceText(selectedSnapshot.fallbackSource) }}
                    </span>
                    <span v-if="selectedSnapshot.fallbackReason">
                      原因：{{ formatFallbackReason(selectedSnapshot.fallbackReason) }}
                    </span>
                  </div>
                </div>

                <div v-if="selectedSnapshot.message" class="message-block">
                  <div class="block-label">执行说明</div>
                  <div class="block-content">{{ selectedSnapshot.message }}</div>
                </div>

                <div v-if="selectedSnapshot.inputSummary" class="message-block">
                  <div class="block-top">
                    <div class="block-label">输入快照</div>
                    <a-button type="link" size="small" @click="toggleSection('input')">
                      {{ isSectionExpanded('input') ? '收起' : '展开' }}
                    </a-button>
                  </div>
                  <div class="block-content code-like">
                    {{ renderBlockContent(selectedSnapshot.inputSummary, 'input') }}
                  </div>
                </div>

                <div v-if="selectedSnapshot.outputSummary" class="message-block">
                  <div class="block-top">
                    <div class="block-label">输出快照</div>
                    <a-button type="link" size="small" @click="toggleSection('output')">
                      {{ isSectionExpanded('output') ? '收起' : '展开' }}
                    </a-button>
                  </div>
                  <div class="block-content code-like">
                    {{ renderBlockContent(selectedSnapshot.outputSummary, 'output') }}
                  </div>
                </div>

                <div v-if="selectedSnapshot.errorMessage" class="message-block error">
                  <div class="block-top">
                    <div class="block-label">错误信息</div>
                    <a-button type="link" size="small" @click="toggleSection('error')">
                      {{ isSectionExpanded('error') ? '收起' : '展开' }}
                    </a-button>
                  </div>
                  <div class="block-content">
                    {{ renderBlockContent(selectedSnapshot.errorMessage, 'error', 220) }}
                  </div>
                </div>
              </div>
            </template>
            <a-empty
              v-else
              :image="false"
              description="请选择一个节点查看详情"
              class="empty-state"
            />
          </div>
        </div>

        <div v-if="filteredSnapshots.length > 0 && viewMode === 'timeline'" class="timeline">
          <div
            v-for="(item, index) in filteredSnapshots"
            :key="item.snapshotId || `${item.node || 'node'}-${index}`"
            :class="[
              'timeline-item',
              resolveStatusClass(item.status),
              { 'is-selected': selectedSnapshot?.snapshotId === item.snapshotId },
            ]"
            @click="selectSnapshot(item)"
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

              <div
                v-if="item.decisionSummary || item.decisionSource || item.decisionReason"
                class="message-block"
              >
                <div class="block-label">路由决策</div>
                <div v-if="item.decisionSummary" class="block-content">
                  {{ item.decisionSummary }}
                </div>
                <div v-if="item.decisionSource || item.decisionReason" class="meta-line">
                  <span v-if="item.decisionSource">
                    来源：{{ getDecisionSourceText(item.decisionSource) }}
                  </span>
                  <span v-if="item.decisionReason">
                    原因：{{ formatDecisionReason(item.decisionReason) }}
                  </span>
                </div>
              </div>

              <div
                v-if="item.memoryContextSummary"
                class="message-block"
              >
                <div class="block-label">记忆上下文</div>
                <div class="block-content">
                  {{ item.memoryContextSummary }}
                </div>
              </div>

              <div
                v-if="item.fallbackSummary || item.fallbackSource || item.fallbackReason"
                class="message-block"
              >
                <div class="block-label">降级策略</div>
                <div v-if="item.fallbackSummary" class="block-content">
                  {{ formatFallbackSummary(item.fallbackSummary) }}
                </div>
                <div v-if="item.fallbackSource || item.fallbackReason" class="meta-line">
                  <span v-if="item.fallbackSource">
                    来源：{{ getFallbackSourceText(item.fallbackSource) }}
                  </span>
                  <span v-if="item.fallbackReason">
                    原因：{{ formatFallbackReason(item.fallbackReason) }}
                  </span>
                </div>
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

        <div v-else-if="filteredSnapshots.length > 0" class="grouped-list">
          <div
            v-for="group in groupedSnapshots"
            :key="group.node"
            class="group-card"
          >
            <div class="group-top">
              <div class="group-heading">
                <span class="group-title">{{ getNodeText(group.node) }}</span>
                <a-tag color="blue">{{ group.items.length }} 次执行</a-tag>
                <a-tag v-if="group.failedCount > 0" color="error">{{ group.failedCount }} 次失败</a-tag>
              </div>
              <span class="group-meta">最新时间：{{ formatTimestamp(group.latestAt) }}</span>
            </div>

            <div class="group-items">
              <div
                v-for="item in group.items"
                :key="item.snapshotId || `${group.node}-${item.startedAt}`"
                :class="[
                  'group-item',
                  { 'is-selected': selectedSnapshot?.snapshotId === item.snapshotId },
                ]"
                @click="selectSnapshot(item)"
              >
                <div class="group-item-top">
                  <div class="group-item-heading">
                    <a-tag :color="getStatusColor(item.status)">{{ getStatusText(item.status) }}</a-tag>
                    <a-tag v-if="item.replayable" color="gold">可回放</a-tag>
                    <span class="group-item-time">{{ formatTimestamp(item.startedAt) }}</span>
                  </div>
                  <span class="group-item-duration">{{ item.elapsedMs ?? 0 }}ms</span>
                </div>
                <div class="group-item-detail">
                  {{ item.message || item.decisionSummary || item.errorMessage || '暂无摘要' }}
                </div>
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
type ViewMode = 'timeline' | 'grouped'
type ExpandableSection = 'input' | 'output' | 'error' | 'memory'

const loading = ref(false)
const snapshots = ref<API.NodeReplaySnapshotVO[]>([])
const selectedSnapshotId = ref<string>('')
const statusFilter = ref<StatusFilter>('ALL')
const viewMode = ref<ViewMode>('timeline')
const nodeFilter = ref<string>('ALL')
const keyword = ref('')
const expandedSections = ref<Record<ExpandableSection, boolean>>({
  input: false,
  output: false,
  error: true,
  memory: false,
})
let refreshTimer: ReturnType<typeof setInterval> | null = null

const statusOptions = [
  { label: '全部状态', value: 'ALL' },
  { label: '仅失败节点', value: 'FAILED' },
  { label: '仅可回放', value: 'REPLAYABLE' },
  { label: '执行中', value: 'RUNNING' },
  { label: '成功节点', value: 'SUCCESS' },
]

const viewModeOptions = [
  { label: '时间线', value: 'timeline' },
  { label: '按节点分组', value: 'grouped' },
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

const groupedSnapshots = computed(() => {
  const groups = new Map<string, API.NodeReplaySnapshotVO[]>()
  filteredSnapshots.value.forEach((item) => {
    const key = item.node || 'UNKNOWN'
    const current = groups.get(key) || []
    current.push(item)
    groups.set(key, current)
  })
  return Array.from(groups.entries()).map(([node, items]) => ({
    node,
    items,
    failedCount: items.filter(item => item.status === 'FAILED').length,
    latestAt: items[0]?.finishedAt ?? items[0]?.startedAt ?? 0,
  }))
})

const failureReasonItems = computed(() => {
  const counts = new Map<string, number>()
  filteredSnapshots.value
    .filter(item => item.status === 'FAILED')
    .forEach((item) => {
      const label = resolveFailureReasonLabel(item)
      if (!label) {
        return
      }
      counts.set(label, (counts.get(label) || 0) + 1)
    })
  return Array.from(counts.entries())
    .map(([label, count]) => ({ label, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 5)
})

const latestSnapshot = computed(() => filteredSnapshots.value[0] || null)

const slowestSnapshot = computed(() => {
  return [...filteredSnapshots.value]
    .sort((a, b) => (b.elapsedMs ?? 0) - (a.elapsedMs ?? 0))[0] || null
})

const latestFailedSnapshot = computed(() => {
  return filteredSnapshots.value.find(item => item.status === 'FAILED') || null
})

const selectedSnapshot = computed(() => {
  if (selectedSnapshotId.value) {
    const matched = filteredSnapshots.value.find(item => item.snapshotId === selectedSnapshotId.value)
    if (matched) {
      return matched
    }
  }
  return filteredSnapshots.value[0] || null
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
    if (!selectedSnapshotId.value && snapshots.value.length > 0) {
      selectedSnapshotId.value = snapshots.value[0].snapshotId || ''
    }
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

const selectSnapshot = (snapshot: API.NodeReplaySnapshotVO) => {
  selectedSnapshotId.value = snapshot.snapshotId || ''
}

const toggleSection = (section: ExpandableSection) => {
  expandedSections.value[section] = !expandedSections.value[section]
}

const isSectionExpanded = (section: ExpandableSection) => {
  return expandedSections.value[section]
}

const renderBlockContent = (value?: string, section?: ExpandableSection, limit = 360) => {
  if (!value) {
    return '--'
  }
  if (section && isSectionExpanded(section)) {
    return value
  }
  return shortenText(value, limit)
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

const formatMemoryContextSnapshot = (value?: string) => {
  if (!value) {
    return '--'
  }
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
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
    agent3_review_content: '正文评审',
    image_strategy_router: '图片策略路由',
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

const getDecisionSourceText = (source?: string) => {
  const sourceMap: Record<string, string> = {
    state_selection: '\u5f53\u524d\u4efb\u52a1\u9009\u62e9',
    user_preference: '\u7528\u6237\u504f\u597d\u8bb0\u5fc6',
    task_memory: '\u4efb\u52a1\u8bb0\u5fc6\u590d\u7528',
    default_policy: '\u9ed8\u8ba4\u7b56\u7565',
    content_reviewer: '\u6b63\u6587\u8bc4\u5ba1\u5668',
    content_reviewer_keep: '\u6b63\u6587\u8bc4\u5ba1\u5668\uff08\u4fdd\u7559\u539f\u6587\uff09',
  }
  return sourceMap[source || ''] || source || '--'
}

const formatDecisionReason = (reason?: string) => {
  if (!reason) {
    return '--'
  }
  const reasonMap: Record<string, string> = {
    reuse_current_state_methods: '\u590d\u7528\u5f53\u524d\u4efb\u52a1\u5df2\u9009\u56fe\u7247\u65b9\u6cd5',
    apply_user_preferred_methods: '\u5e94\u7528\u7528\u6237\u5386\u53f2\u504f\u597d\u56fe\u7247\u65b9\u6cd5',
    reuse_task_memory_methods: '\u590d\u7528\u5f53\u524d\u4efb\u52a1\u6c89\u6dc0\u7684\u56fe\u7247\u65b9\u6cd5',
    apply_default_image_policy: '\u5e94\u7528\u7cfb\u7edf\u9ed8\u8ba4\u56fe\u7247\u7b56\u7565',
    diagram_signal_detected: '\u68c0\u6d4b\u5230\u6d41\u7a0b / \u67b6\u6784 / \u56fe\u89e3\u7c7b\u5185\u5bb9\u4fe1\u53f7\uff0c\u4f18\u5148\u56fe\u8868\u578b\u65b9\u6cd5',
    realistic_signal_detected: '\u68c0\u6d4b\u5230\u573a\u666f / \u4ea7\u54c1 / \u4eba\u7269\u7c7b\u5185\u5bb9\u4fe1\u53f7\uff0c\u4f18\u5148\u5199\u5b9e\u914d\u56fe\u65b9\u6cd5',
    task_missing_fallback: '\u4efb\u52a1\u4e0a\u4e0b\u6587\u7f3a\u5931\uff0c\u56de\u9000\u5230\u9ed8\u8ba4\u56fe\u7247\u7b56\u7565',
    content_reviewed: '\u5df2\u5b8c\u6210\u6b63\u6587\u8bc4\u5ba1',
    content_revised: '\u5df2\u6267\u884c\u6700\u5c0f\u4fee\u8ba2',
    duplicate_reduced: '\u91cd\u590d\u8868\u8fbe\u5df2\u6536\u655b',
    ending_strengthened: '\u7ed3\u5c3e\u6536\u675f\u5df2\u589e\u5f3a',
    outline_alignment_checked: '\u5927\u7eb2\u5bf9\u9f50\u5df2\u68c0\u67e5',
    '\u91cd\u590d\u8868\u8ff0': '\u91cd\u590d\u8868\u8ff0',
    '\u7ed3\u5c3e\u504f\u5f31': '\u7ed3\u5c3e\u504f\u5f31',
    '\u504f\u79bb\u5927\u7eb2': '\u504f\u79bb\u5927\u7eb2',
    '\u8fc7\u6e21\u4e0d\u8db3': '\u6bb5\u843d\u8fc7\u6e21\u4e0d\u8db3',
    '\u7a7a\u6cdb\u63cf\u8ff0': '\u5185\u5bb9\u8f83\u7a7a\u6cdb',
  }
  return reason
    .split('|')
    .map(item => reasonMap[item] || item)
    .join('\uff1b')
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

const getFallbackSourceText = (source?: string) => {
  const sourceMap: Record<string, string> = {
    image_fallback_router: '图片失败降级路由',
  }
  return sourceMap[source || ''] || source || '--'
}

const formatFallbackReason = (reason?: string) => {
  if (!reason) {
    return '--'
  }
  const reasonMap: Record<string, string> = {
    image_generation_failed: '主图片方法执行失败，已进入降级链路',
    missing_requested_method: '缺少原始图片方法，直接走兜底策略',
    single_method_only: '当前只有单一候选方法，未触发额外降级',
    diagram_render_failed: '图表渲染失败，回退到其他图片方法',
    svg_generation_failed: 'SVG 生成失败，回退到其他图片方法',
    ai_image_generation_failed: 'AI 生图失败，回退到其他图片方法',
    stock_image_search_failed: '图库检索失败，回退到其他图片方法',
    icon_search_failed: '图标检索失败，回退到其他图片方法',
    emoji_search_failed: '表情图检索失败，回退到其他图片方法',
    fallback_route_applied: '已按规则执行图片降级路线',
  }
  return reasonMap[reason] || reason
}

const formatFallbackSummary = (summary?: string) => {
  if (!summary) {
    return '--'
  }
  return summary.replace(/,/g, '；')
}

const resolveFailureReasonLabel = (item: API.NodeReplaySnapshotVO) => {
  if (item.errorMessage) {
    return shortenText(item.errorMessage, 64)
  }
  if (item.fallbackReason) {
    return formatFallbackReason(item.fallbackReason)
  }
  if (item.decisionReason) {
    return formatDecisionReason(item.decisionReason)
  }
  if (item.message) {
    return shortenText(item.message, 64)
  }
  return ''
}

const shortenText = (value?: string, maxLength = 120) => {
  if (!value) {
    return '--'
  }
  const normalized = value.replace(/\s+/g, ' ').trim()
  if (normalized.length <= maxLength) {
    return normalized
  }
  return `${normalized.slice(0, maxLength)}...`
}

watch(
  () => props.taskId,
  (taskId) => {
    if (!taskId) {
      snapshots.value = []
      selectedSnapshotId.value = ''
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

watch(
  filteredSnapshots,
  (items) => {
    if (items.length === 0) {
      selectedSnapshotId.value = ''
      return
    }
    const matched = items.some(item => item.snapshotId === selectedSnapshotId.value)
    if (!matched) {
      selectedSnapshotId.value = items[0].snapshotId || ''
    }
  },
  { immediate: true },
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

.view-mode-switch {
  min-width: 220px;
}

.filter-summary {
  margin-top: 10px;
  font-size: 12px;
  color: var(--color-text-muted);
}

.inspector-grid {
  display: grid;
  grid-template-columns: minmax(280px, 0.85fr) minmax(0, 1.15fr);
  gap: 16px;
}

.inspector-summary,
.inspector-detail {
  padding: 14px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid var(--color-border);
}

.summary-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.summary-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--color-text);
}

.summary-subtitle {
  font-size: 12px;
  color: var(--color-text-muted);
}

.summary-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.mini-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 14px;
  background: var(--color-background-secondary);
  border: 1px solid var(--color-border-light);
}

.mini-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

.mini-value {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text);
  word-break: break-word;
}

.summary-tip {
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.summary-section {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--color-border-light);
}

.summary-section-title {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text);
}

.summary-empty {
  font-size: 12px;
  color: var(--color-text-muted);
}

.reason-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.reason-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--color-background-secondary);
  border: 1px solid var(--color-border-light);
}

.reason-label {
  flex: 1;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text-secondary);
  word-break: break-word;
}

.detail-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.detail-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.detail-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--color-text);
}

.detail-duration {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 700;
  color: var(--color-primary);
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--color-text-muted);
}

.detail-sections {
  margin-top: 12px;
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
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.timeline-content:hover {
  border-color: rgba(124, 58, 237, 0.24);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}

.timeline-item.is-selected .timeline-content {
  border-color: rgba(124, 58, 237, 0.32);
  box-shadow: 0 12px 28px rgba(124, 58, 237, 0.12);
  transform: translateY(-1px);
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

.block-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.block-label {
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

.grouped-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.group-card {
  padding: 14px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid var(--color-border);
}

.group-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}

.group-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.group-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--color-text);
}

.group-meta {
  font-size: 12px;
  color: var(--color-text-muted);
}

.group-items {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.group-item {
  padding: 12px;
  border-radius: 14px;
  background: var(--color-background-secondary);
  border: 1px solid var(--color-border-light);
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.group-item:hover,
.group-item.is-selected {
  border-color: rgba(124, 58, 237, 0.28);
  box-shadow: 0 10px 20px rgba(124, 58, 237, 0.08);
}

.group-item-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.group-item-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.group-item-time {
  font-size: 12px;
  color: var(--color-text-muted);
}

.group-item-duration {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 700;
  color: var(--color-primary);
}

.group-item-detail {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-secondary);
  word-break: break-word;
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

  .inspector-grid,
  .summary-cards {
    grid-template-columns: 1fr;
  }

  .filter-controls {
    flex-direction: column;
  }

  .filter-select,
  .filter-select-wide,
  .filter-input,
  .view-mode-switch {
    width: 100%;
  }

  .panel-header,
  .timeline-top,
  .group-top,
  .group-item-top,
  .detail-top {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
