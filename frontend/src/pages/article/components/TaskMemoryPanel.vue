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
              <span class="summary-label">是否需要图片</span>
              <span class="summary-value">{{ getNeedImagesText(memory.imageStrategy?.needImages) }}</span>
            </div>
            <div class="summary-card muted">
              <span class="summary-label">决策来源</span>
              <span class="summary-value">{{ getDecisionSourceText(memory.imageStrategy?.decisionSource) }}</span>
            </div>
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
            <div class="summary-card muted">
              <span class="summary-label">降级次数</span>
              <span class="summary-value">{{ memory.imageStrategy?.fallbackCount ?? 0 }}</span>
            </div>
          </div>
          <div v-if="memory.imageStrategy?.decisionReason" class="summary-block">
            <div class="summary-block-title">路由原因</div>
            <div class="summary-text">{{ formatDecisionReason(memory.imageStrategy?.decisionReason) }}</div>
          </div>
          <div v-if="memory.imageStrategy?.fallbackSummary" class="summary-block">
            <div class="summary-block-title">降级摘要</div>
            <div class="summary-text">{{ formatFallbackSummary(memory.imageStrategy?.fallbackSummary) }}</div>
          </div>
        </div>

        <div class="memory-group">
          <div class="group-title">
            <CheckCircleOutlined />
            <span>&#27491;&#25991;&#35780;&#23457;</span>
          </div>
          <div v-if="memory.contentReview" class="summary-grid summary-grid--strategy">
            <div class="summary-card muted">
              <span class="summary-label">&#26159;&#21542;&#24314;&#35758;&#20462;&#35746;</span>
              <span class="summary-value">{{ getBooleanText(memory.contentReview?.needsRevision) }}</span>
            </div>
            <div class="summary-card muted">
              <span class="summary-label">&#26159;&#21542;&#24050;&#24212;&#29992;&#20462;&#35746;</span>
              <span class="summary-value">{{ getBooleanText(memory.contentReview?.revised) }}</span>
            </div>
            <div class="summary-card muted">
              <span class="summary-label">&#38382;&#39064;&#25968;&#37327;</span>
              <span class="summary-value">{{ memory.contentReview?.issueCount ?? 0 }}</span>
            </div>
            <div class="summary-card muted">
              <span class="summary-label">&#35780;&#23457;&#20449;&#21495;</span>
              <span class="summary-value">{{ formatReviewSignalList(memory.contentReview?.qualitySignals) }}</span>
            </div>
          </div>
          <div v-if="memory.contentReview?.summary" class="summary-block">
            <div class="summary-block-title">&#35780;&#23457;&#25688;&#35201;</div>
            <div class="summary-text">{{ memory.contentReview.summary }}</div>
          </div>
          <div v-if="memory.contentReview?.issues?.length" class="summary-block">
            <div class="summary-block-title">&#21457;&#29616;&#38382;&#39064;</div>
            <div class="chip-list">
              <a-tag v-for="item in memory.contentReview.issues" :key="item" color="orange">
                {{ formatReviewIssue(item) }}
              </a-tag>
            </div>
          </div>
          <div v-if="memory.contentReview?.qualitySignals?.length" class="summary-block">
            <div class="summary-block-title">&#36136;&#37327;&#25913;&#36827;&#20449;&#21495;</div>
            <div class="chip-list">
              <a-tag v-for="item in memory.contentReview.qualitySignals" :key="item" color="green">
                {{ formatReviewSignal(item) }}
              </a-tag>
            </div>
          </div>
          <div v-else class="empty-text">&#26242;&#26080;&#27491;&#25991;&#35780;&#23457;&#32467;&#26524;</div>
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
    agent3_review_content: '正文评审',
    image_strategy_router: '图片策略路由',
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

const getNeedImagesText = (needImages?: boolean) => {
  if (needImages === true) {
    return '\u9700\u8981'
  }
  if (needImages === false) {
    return '\u4e0d\u9700\u8981'
  }
  return '--'
}

const getBooleanText = (value?: boolean) => {
  if (value === true) {
    return '\u662f'
  }
  if (value === false) {
    return '\u5426'
  }
  return '--'
}

const formatReviewSignal = (signal?: string) => {
  const signalMap: Record<string, string> = {
    content_reviewed: '\u5df2\u5b8c\u6210\u6b63\u6587\u8bc4\u5ba1',
    content_revised: '\u5df2\u6267\u884c\u6700\u5c0f\u4fee\u8ba2',
    duplicate_reduced: '\u91cd\u590d\u8868\u8fbe\u5df2\u6536\u655b',
    ending_strengthened: '\u7ed3\u5c3e\u6536\u675f\u5df2\u589e\u5f3a',
    outline_alignment_checked: '\u5927\u7eb2\u5bf9\u9f50\u5df2\u68c0\u67e5',
  }
  return signalMap[signal || ''] || signal || '--'
}

const formatReviewSignalList = (signals?: string[]) => {
  return signals && signals.length > 0
    ? signals.map(item => formatReviewSignal(item)).join(' / ')
    : '--'
}

const formatReviewIssue = (issue?: string) => {
  const issueMap: Record<string, string> = {
    '\u91cd\u590d\u8868\u8ff0': '\u91cd\u590d\u8868\u8ff0',
    '\u7ed3\u5c3e\u504f\u5f31': '\u7ed3\u5c3e\u504f\u5f31',
    '\u504f\u79bb\u5927\u7eb2': '\u504f\u79bb\u5927\u7eb2',
    '\u8fc7\u6e21\u4e0d\u8db3': '\u6bb5\u843d\u8fc7\u6e21\u4e0d\u8db3',
    '\u7a7a\u6cdb\u63cf\u8ff0': '\u5185\u5bb9\u8f83\u7a7a\u6cdb',
  }
  return issueMap[issue || ''] || issue || '--'
}

const getDecisionSourceText = (source?: string) => {
  const sourceMap: Record<string, string> = {
    state_selection: '当前任务选择',
    user_preference: '用户偏好记忆',
    task_memory: '任务记忆复用',
    default_policy: '默认策略',
  }
  return sourceMap[source || ''] || source || '--'
}

const formatDecisionReason = (reason?: string) => {
  if (!reason) {
    return '--'
  }
  const reasonMap: Record<string, string> = {
    reuse_current_state_methods: '复用当前任务已选图片方法',
    apply_user_preferred_methods: '应用用户历史偏好图片方法',
    reuse_task_memory_methods: '复用当前任务沉淀的图片方法',
    apply_default_image_policy: '应用系统默认图片策略',
    diagram_signal_detected: '检测到流程 / 架构 / 图解类内容信号，优先图表型方法',
    realistic_signal_detected: '检测到场景 / 产品 / 人物类内容信号，优先写实配图方法',
    task_missing_fallback: '任务上下文缺失，回退到默认图片策略',
  }
  return reason
    .split('|')
    .map(item => reasonMap[item] || item)
    .join('；')
}

const formatFallbackSummary = (summary?: string) => {
  if (!summary) {
    return '--'
  }
  return summary.replace(/,/g, '；')
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
