<template>
  <div class="article-create-page">
    <!-- 三栏布局容器 -->
    <div class="create-layout">
      <!-- 左侧：智能体流程可视化 -->
      <aside class="sidebar-left">
        <div class="sidebar-header">
          <h3 class="sidebar-title">创作流程</h3>
          <p class="sidebar-subtitle">智能体协作可视化</p>
        </div>

        <div class="flow-timeline">
          <div
            v-for="(step, index) in agentSteps"
            :key="index"
            :class="['flow-item', {
              'active': currentStep === index,
              'completed': currentStep > index,
              'pending': currentStep < index
            }]"
          >
            <div class="flow-indicator">
              <LoadingOutlined v-if="currentStep === index && isCreating" class="spin-icon" />
              <CheckCircleOutlined v-else-if="currentStep > index" />
              <span v-else class="step-number">{{ index + 1 }}</span>
            </div>
            <div class="flow-content">
              <div class="flow-title">{{ step.title }}</div>
              <div class="flow-desc">{{ step.description }}</div>
              <div v-if="currentStep === index && isCreating" class="flow-status">
                <span class="status-dot"></span>
                执行中...
              </div>
            </div>
          </div>
        </div>

      </aside>

      <!-- 中间：主内容区 -->
      <main ref="mainContentRef" class="main-content">
        <!-- 阶段切换（带过渡动画） -->
        <Transition name="fade-slide" mode="out-in">
          <!-- 输入状态 -->
          <div v-if="currentPhase === 'INPUT'" key="input" class="input-state">
          <div class="input-card">
            <div class="input-header">
              <h1 class="input-title">创作新文章</h1>
              <p class="input-subtitle">输入选题，AI 帮你生成爆款文章</p>
            </div>

            <div class="input-area">
              <a-textarea
                v-model:value="topic"
                placeholder="请输入您想创作的文章选题，例如：2026年AI如何改变职场"
                :rows="6"
                :maxlength="500"
                show-count
                class="topic-textarea"
              />

              <!-- 文章风格选择 -->
              <div class="style-section">
                <div class="section-header">
                  <span class="section-title">文章风格</span>
                  <span class="section-tip">（不选择使用默认风格）</span>
                </div>
                <a-radio-group v-model:value="selectedStyle" class="style-group">
                  <a-radio value="">默认</a-radio>
                  <a-radio value="tech">科技风格</a-radio>
                  <a-radio value="emotional">情感风格</a-radio>
                  <a-radio value="educational">教育风格</a-radio>
                  <a-radio value="humorous">轻松幽默</a-radio>
                </a-radio-group>
              </div>

              <!-- 配图方式选择 -->
              <div class="image-methods-section">
                <div class="section-header">
                  <span class="section-title">配图方式</span>
                  <span class="section-tip">（不选择表示支持所有方式）</span>
                </div>
                <a-checkbox-group v-model:value="selectedImageMethods" class="methods-group">
                  <a-checkbox value="PEXELS">Pexels</a-checkbox>
                  <a-tooltip :title="isVip ? '' : '仅限 VIP 会员'">
                    <a-checkbox value="NANO_BANANA" :disabled="!isVip">
                      Nano Banana
                      <CrownOutlined v-if="!isVip" class="vip-icon" />
                    </a-checkbox>
                  </a-tooltip>
                  <a-checkbox value="MERMAID">Mermaid</a-checkbox>
                  <a-checkbox value="ICONIFY">Iconify</a-checkbox>
                  <a-checkbox value="EMOJI_PACK">表情包</a-checkbox>
                  <a-tooltip :title="isVip ? '' : '仅限 VIP 会员'">
                    <a-checkbox value="SVG_DIAGRAM" :disabled="!isVip">
                      SVG
                      <CrownOutlined v-if="!isVip" class="vip-icon" />
                    </a-checkbox>
                  </a-tooltip>
                </a-checkbox-group>
                <div v-if="!isVip" class="vip-notice">
                  <CrownOutlined />
                  <span>AI 生图和 SVG 图表为 VIP 专属功能，</span>
                  <RouterLink to="/vip" class="upgrade-link">立即升级</RouterLink>
                </div>
              </div>

              <a-button
                type="primary"
                size="large"
                :loading="isCreating"
                :disabled="!topic.trim() || !hasQuota"
                @click="startCreate"
                class="create-btn"
              >
                <template #icon>
                  <RocketOutlined />
                </template>
                开始创作
              </a-button>
              <div v-if="!hasQuota" class="quota-warning">
                <WarningOutlined />
                <span>配额已用完，无法创建文章</span>
              </div>
            </div>
          </div>
          </div>

          <!-- 标题生成中 -->
          <div v-else-if="currentPhase === 'TITLE_GENERATING'" key="title-generating" class="loading-stage">
            <a-spin size="large" />
            <h3>AI 正在生成标题方案...</h3>
            <p>稍等片刻，即将为您呈现多个精彩标题</p>
          </div>

          <!-- 标题选择阶段 -->
          <TitleSelectingStage
            v-else-if="currentPhase === 'TITLE_SELECTING'"
            key="title-selecting"
            :title-options="titleOptions"
            :loading="confirmLoading"
            @confirm="handleConfirmTitle"
          />

          <!-- 大纲生成中（流式展示） -->
          <div v-else-if="currentPhase === 'OUTLINE_GENERATING'" key="outline-generating" class="outline-generating-state">
            <!-- 标题预览 -->
            <div v-if="article.mainTitle" class="preview-header">
              <h1 class="article-title">{{ article.mainTitle }}</h1>
              <p class="article-subtitle">{{ article.subTitle }}</p>
            </div>

            <!-- 大纲流式展示 -->
            <div class="outline-preview">
              <div class="section-label">
                <BulbOutlined />
                <span>AI 正在规划文章大纲</span>
                <span class="typing-cursor">|</span>
              </div>
              <div v-if="parsedOutline.length > 0" class="outline-list">
                <div
                  v-for="item in parsedOutline"
                  :key="item.section"
                  class="outline-item fade-in"
                >
                  <div class="outline-title">{{ item.section }}. {{ item.title }}</div>
                  <ul class="outline-points">
                    <li v-for="(point, idx) in item.points" :key="idx">{{ point }}</li>
                  </ul>
                </div>
              </div>
              <div v-else class="outline-loading">
                <a-spin />
                <span>正在构建文章结构...</span>
              </div>
            </div>
          </div>

          <!-- 大纲编辑阶段 -->
          <OutlineEditingStage
            v-else-if="currentPhase === 'OUTLINE_EDITING'"
            key="outline-editing"
            :outline="outline"
            :loading="confirmLoading"
            :task-id="taskId"
            @confirm="handleConfirmOutline"
          />

          <!-- 正文生成阶段 -->
          <div v-else-if="currentPhase === 'CONTENT_GENERATING'" key="content-generating" class="creating-state">
          <!-- 标题预览 -->
          <div v-if="article.mainTitle" class="preview-header">
            <h1 class="article-title">{{ article.mainTitle }}</h1>
            <p class="article-subtitle">{{ article.subTitle }}</p>
          </div>

          <!-- 大纲预览（流式解析展示） -->
          <div v-if="outlineRaw" class="outline-preview">
            <div class="section-label">
              <BulbOutlined />
              <span>文章大纲</span>
              <span v-if="isOutlineStreaming" class="typing-cursor">|</span>
            </div>
            <div class="outline-list">
              <div
                v-for="item in parsedOutline"
                :key="item.section"
                class="outline-item"
              >
                <div class="outline-title">{{ item.section }}. {{ item.title }}</div>
                <ul class="outline-points">
                  <li v-for="(point, idx) in item.points" :key="idx">{{ point }}</li>
                </ul>
              </div>
            </div>
          </div>

          <!-- 正文预览（流式输出） -->
          <div v-if="article.content" class="content-preview">
            <div v-html="markdownToHtml(article.content)" class="markdown-body"></div>
            <span v-if="isStreaming" class="typing-cursor">|</span>
          </div>

          <!-- 配图进度 -->
          <div v-if="currentStep === 4 && imageProgress > 0" class="image-progress-box">
            <div class="progress-header">
              <PictureOutlined />
              <span>正在生成配图</span>
            </div>
            <a-progress :percent="imageProgress" status="active" :stroke-color="{ from: '#22C55E', to: '#16A34A' }" />
            <p class="progress-hint">{{ imageCount }}/{{ totalImages }} 张图片已完成</p>
          </div>

          <!-- 加载占位 -->
          <div v-if="currentStep === 0 && !article.mainTitle" class="loading-placeholder">
            <a-spin size="large" />
            <p>AI 正在构思标题...</p>
          </div>
          </div>

          <!-- 创作完成 -->
          <div v-else-if="currentPhase === 'COMPLETED'" key="completed" class="completed-state">
          <div class="success-header">
            <CheckCircleFilled class="success-icon" />
            <span>文章创作完成！</span>
          </div>

          <div class="preview-header">
            <h1 class="article-title">{{ article.mainTitle }}</h1>
            <p class="article-subtitle">{{ article.subTitle }}</p>
          </div>
          <div class="content-preview">
            <div v-html="markdownToHtml(article.fullContent || article.content || '')" class="markdown-body"></div>
          </div>
          </div>
        </Transition>
      </main>

      <!-- 右侧：辅助面板 -->
      <aside class="sidebar-right">
        <!-- 配额信息 -->
        <div v-if="currentPhase === 'INPUT'" class="panel-section quota-section">
          <h4 class="panel-title">
            <CrownOutlined />
            创作配额
          </h4>
          <div v-if="isAdmin" class="quota-admin">
            <span class="quota-badge admin">管理员</span>
            <span class="quota-text">无限次</span>
          </div>
          <div v-else-if="isVip" class="quota-admin">
            <span class="quota-badge vip">VIP 会员</span>
            <span class="quota-text">无限次</span>
          </div>
          <div v-else class="quota-info">
            <div class="quota-display">
              <span class="quota-number" :class="{ 'low': quota <= 1, 'empty': quota === 0 }">{{ quota }}</span>
              <span class="quota-unit">次</span>
            </div>
            <div class="quota-label">剩余可用</div>
            <a-progress
              :percent="(quota / 5) * 100"
              :show-info="false"
              :stroke-color="quota <= 1 ? '#ff4d4f' : '#22C55E'"
              size="small"
              class="quota-progress"
            />
          </div>
        </div>

        <!-- 热门选题 -->
        <div v-if="currentPhase === 'INPUT'" class="panel-section">
          <h4 class="panel-title">
            <BulbOutlined />
            热门选题
          </h4>
          <div class="hot-tags">
            <span
              v-for="example in exampleTopics"
              :key="example"
              class="hot-tag"
              @click="topic = example"
            >
              {{ example }}
            </span>
          </div>
        </div>

        <!-- 创作技巧 -->
        <div v-if="currentPhase === 'INPUT'" class="panel-section">
          <h4 class="panel-title">
            <StarOutlined />
            爆款技巧
          </h4>
          <div class="tips-list">
            <div class="tip-item">
              <div class="tip-icon">1</div>
              <div class="tip-content">
                <div class="tip-title">抓住痛点</div>
                <div class="tip-desc">直击用户最关心的问题</div>
              </div>
            </div>
            <div class="tip-item">
              <div class="tip-icon">2</div>
              <div class="tip-content">
                <div class="tip-title">制造悬念</div>
                <div class="tip-desc">让读者产生好奇心</div>
              </div>
            </div>
            <div class="tip-item">
              <div class="tip-icon">3</div>
              <div class="tip-content">
                <div class="tip-title">数字吸引</div>
                <div class="tip-desc">使用具体数据增加说服力</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 创作进行中的提示（所有创作阶段） -->
        <div v-if="isCreating || currentPhase === 'TITLE_SELECTING' || currentPhase === 'OUTLINE_EDITING'" class="panel-section">
          <h4 class="panel-title">
            <ClockCircleOutlined />
            创作进度
          </h4>
          <div class="progress-info">
            <div class="progress-step">
              <span class="step-label">当前步骤</span>
              <span class="step-value">{{ agentSteps[currentStep]?.title }}</span>
            </div>
            <div class="progress-step">
              <span class="step-label">已完成</span>
              <span class="step-value">{{ currentStep }}/{{ agentSteps.length }}</span>
            </div>
          </div>
          <div v-if="isCreating" class="progress-tip">
            <InfoCircleOutlined />
            <span>AI 正在努力创作中，请耐心等待...</span>
          </div>
          <div v-else class="progress-tip waiting">
            <InfoCircleOutlined />
            <span>等待您的确认...</span>
          </div>
        </div>

        <div
          v-if="canResumeCurrentTask || errorVisible"
          class="panel-section resume-section"
        >
          <h4 class="panel-title">
            <RedoOutlined />
            恢复执行
          </h4>
          <div class="resume-card">
            <div class="resume-title">
              {{ errorVisible ? '当前任务执行中断' : '当前任务可继续执行' }}
            </div>
            <div class="resume-desc">
              <span>任务 ID：{{ taskId || lastFailedTaskId || '--' }}</span>
              <span>阶段：{{ latestNodeLog?.phase ? getPhaseDisplayName(latestNodeLog.phase) : currentPhase }}</span>
              <span>状态：{{ articleStatusText.label }}</span>
            </div>
            <a-button
              type="primary"
              block
              :loading="confirmLoading"
              @click="handleResumeTask"
              class="resume-btn"
            >
              <RedoOutlined />
              从当前阶段继续
            </a-button>
          </div>
        </div>

        <!-- 实时执行观测 -->
        <div
          v-if="currentPhase !== 'INPUT' && hasExecutionObservability"
          class="panel-section observability-section"
        >
          <h4 class="panel-title">
            <ThunderboltOutlined />
            执行观测
          </h4>

          <div class="observability-summary">
            <div class="summary-card">
              <span class="summary-label">当前阶段</span>
              <span class="summary-value">
                {{ latestNodeLog?.phase ? getPhaseDisplayName(latestNodeLog.phase) : agentSteps[currentStep]?.title }}
              </span>
            </div>
            <div class="summary-card">
              <span class="summary-label">节点数量</span>
              <span class="summary-value">{{ executionStats?.nodeCount ?? 0 }}</span>
            </div>
            <div class="summary-card">
              <span class="summary-label">节点均耗时</span>
              <span class="summary-value">{{ nodeAverageDuration }}ms</span>
            </div>
            <div class="summary-card">
              <span class="summary-label">总耗时</span>
              <span class="summary-value">{{ executionStats?.totalDurationMs ?? 0 }}ms</span>
            </div>
          </div>

          <div v-if="recentNodeTimeline.length > 0" class="observability-group">
            <div class="observability-group-header">
              <span class="observability-group-title">节点时间线</span>
              <span class="observability-group-subtitle">实时显示工作流节点进度与阶段流转</span>
            </div>
            <div class="mini-timeline">
              <div
                v-for="item in recentNodeTimeline"
                :key="item.key"
                :class="['mini-timeline-item', item.status.toLowerCase()]"
              >
                <div class="mini-timeline-indicator">
                  <CheckCircleOutlined v-if="item.status === 'SUCCESS'" class="timeline-icon success" />
                  <CloseCircleOutlined v-else-if="item.status === 'FAILED'" class="timeline-icon failed" />
                  <InfoCircleOutlined v-else-if="item.status === 'INFO'" class="timeline-icon info" />
                  <LoadingOutlined v-else class="timeline-icon running" />
                </div>
                <div class="mini-timeline-content">
                  <div class="mini-timeline-header">
                    <span class="mini-timeline-title">{{ item.title }}</span>
                    <span class="mini-timeline-duration">{{ item.duration }}ms</span>
                  </div>
                  <div class="mini-timeline-meta">
                    <span class="timeline-phase-tag">{{ item.phase }}</span>
                    <span>{{ item.time }}</span>
                  </div>
                  <div v-if="item.message" class="mini-timeline-message">{{ item.message }}</div>
                  <a-button
                    v-if="item.status === 'FAILED' && item.node"
                    size="small"
                    type="link"
                    class="retry-node-btn"
                    :loading="confirmLoading"
                    @click="handleRetryNode(item.node)"
                  >
                    重试该节点
                  </a-button>
                </div>
              </div>
            </div>
          </div>

          <div v-if="recentAgentTimeline.length > 0" class="observability-group">
            <div class="observability-group-header">
              <span class="observability-group-title">智能体时间线</span>
              <span class="observability-group-subtitle">补充显示 LLM / 工具层执行记录</span>
            </div>
            <div class="mini-timeline compact">
              <div
                v-for="item in recentAgentTimeline"
                :key="item.key"
                :class="['mini-timeline-item', item.status.toLowerCase()]"
              >
                <div class="mini-timeline-indicator">
                  <CheckCircleOutlined v-if="item.status === 'SUCCESS'" class="timeline-icon success" />
                  <CloseCircleOutlined v-else-if="item.status === 'FAILED'" class="timeline-icon failed" />
                  <LoadingOutlined v-else class="timeline-icon running" />
                </div>
                <div class="mini-timeline-content">
                  <div class="mini-timeline-header">
                    <span class="mini-timeline-title">{{ item.title }}</span>
                    <span class="mini-timeline-duration">{{ item.duration }}ms</span>
                  </div>
                  <div class="mini-timeline-meta">
                    <span>{{ getStatusText(item.status) }}</span>
                    <span>{{ item.time }}</span>
                  </div>
                  <div v-if="item.message" class="mini-timeline-message error">{{ item.message }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div
          v-if="taskId && currentPhase !== 'INPUT'"
          class="panel-section memory-section"
        >
          <TaskMemoryPanel
            :task-id="taskId"
            title="任务记忆"
            subtitle="实时查看当前任务沉淀的上下文、摘要、人工动作与失败线索"
            :auto-refresh="isCreating || articleStatusText.raw === 'FAILED'"
            :refresh-interval="2500"
          />
        </div>

        <!-- 实时执行日志 -->
        <div v-if="realtimeLogs.length > 0" class="panel-section realtime-logs-section">
          <h4 class="panel-title">
            <FileTextOutlined />
            执行日志
          </h4>
          <div class="logs-container">
            <div
              v-for="(log, index) in realtimeLogs"
              :key="index"
              :class="['log-entry', log.level]"
            >
              <span class="log-time">{{ formatLogTime(log.timestamp) }}</span>
              <span class="log-message">{{ log.message }}</span>
            </div>
          </div>
        </div>

        <!-- 当前选题提示 -->
        <div v-if="currentPhase !== 'INPUT' && currentPhase !== 'COMPLETED' && topic" class="panel-section">
          <h4 class="panel-title">
            <BulbOutlined />
            创作选题
          </h4>
          <div class="topic-display">
            <p>{{ topic }}</p>
          </div>
        </div>

        <!-- 阶段提示 -->
        <div v-if="currentPhase === 'TITLE_GENERATING'" class="panel-section tips-section">
          <h4 class="panel-title">
            <StarOutlined />
            提示
          </h4>
          <div class="tips-list">
            <div class="tip-item">
              <div class="tip-icon">💡</div>
              <div class="tip-content">
                <div class="tip-desc">AI 正在分析您的选题，生成多个吸引眼球的标题方案</div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="currentPhase === 'TITLE_SELECTING'" class="panel-section tips-section">
          <h4 class="panel-title">
            <StarOutlined />
            提示
          </h4>
          <div class="tips-list">
            <div class="tip-item">
              <div class="tip-icon">✅</div>
              <div class="tip-content">
                <div class="tip-desc">选择最符合您期望的标题，或添加补充描述让 AI 更好地理解您的需求</div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="currentPhase === 'OUTLINE_GENERATING'" class="panel-section tips-section">
          <h4 class="panel-title">
            <StarOutlined />
            提示
          </h4>
          <div class="tips-list">
            <div class="tip-item">
              <div class="tip-icon">📝</div>
              <div class="tip-content">
                <div class="tip-desc">AI 正在为您规划文章结构，构建清晰的章节脉络</div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="currentPhase === 'OUTLINE_EDITING'" class="panel-section tips-section">
          <h4 class="panel-title">
            <StarOutlined />
            编辑技巧
          </h4>
          <div class="tips-list">
            <div class="tip-item">
              <div class="tip-icon">1</div>
              <div class="tip-content">
                <div class="tip-title">拖动排序</div>
                <div class="tip-desc">点击章节左侧拖动图标可调整章节顺序</div>
              </div>
            </div>
            <div class="tip-item">
              <div class="tip-icon">2</div>
              <div class="tip-content">
                <div class="tip-title">AI 助手</div>
                <div class="tip-desc">使用 AI 助手快速修改大纲结构</div>
              </div>
            </div>
            <div class="tip-item">
              <div class="tip-icon">3</div>
              <div class="tip-content">
                <div class="tip-title">添加章节</div>
                <div class="tip-desc">根据需要添加或删除章节和要点</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 操作按钮 -->
        <div v-if="currentPhase === 'COMPLETED'" class="panel-section">
          <h4 class="panel-title">
            <ThunderboltOutlined />
            快捷操作
          </h4>
          <div class="action-list">
            <a-button block @click="copyContent" class="action-btn">
              <CopyOutlined />
              复制全文
            </a-button>
            <a-button block @click="viewArticle" class="action-btn">
              <EyeOutlined />
              查看详情
            </a-button>
            <a-button block type="primary" @click="resetCreate" class="action-btn primary">
              <RedoOutlined />
              再创作一篇
            </a-button>
          </div>
        </div>

        <!-- 完成后的统计 -->
        <div v-if="currentPhase === 'COMPLETED'" class="panel-section stats-section">
          <h4 class="panel-title">
            <BarChartOutlined />
            文章统计
          </h4>
          <div class="stats-grid">
            <div class="stat-item">
              <div class="stat-value">{{ (article.fullContent || article.content || '').length }}</div>
              <div class="stat-label">字数</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ article.images?.length || 0 }}</div>
              <div class="stat-label">配图</div>
            </div>
          </div>
        </div>

        <!-- 底部帮助链接 -->
        <div class="panel-footer">
          <a class="help-link">
            <QuestionCircleOutlined />
            使用帮助
          </a>
          <a class="help-link">
            <MessageOutlined />
            反馈建议
          </a>
        </div>
      </aside>
    </div>

    <!-- 错误提示 -->
    <a-modal
      v-model:open="errorVisible"
      title="创作失败"
      @ok="errorVisible = false"
    >
      <p>{{ errorMessage }}</p>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  RocketOutlined,
  LoadingOutlined,
  CheckCircleOutlined,
  CheckCircleFilled,
  CopyOutlined,
  EyeOutlined,
  RedoOutlined,
  ThunderboltOutlined,
  BulbOutlined,
  StarOutlined,
  ClockCircleOutlined,
  InfoCircleOutlined,
  BarChartOutlined,
  QuestionCircleOutlined,
  MessageOutlined,
  PictureOutlined,
  WarningOutlined,
  CrownOutlined,
  FileTextOutlined,
} from '@ant-design/icons-vue'
import {
  createArticle,
  confirmTitle,
  confirmOutline,
  getTaskSnapshot,
  getExecutionLogs,
  resumeTask,
  retryNode,
} from '@/api/articleController'
import { closeSSE, connectSSE, type SSEMessage } from '@/utils/sse'
import {
  isAdmin as checkIsAdmin,
  isVip as checkIsVip,
  hasQuota as checkHasQuota,
} from '@/utils/permission'
import { marked } from 'marked'
import TitleSelectingStage from './components/TitleSelectingStage.vue'
import OutlineEditingStage from './components/OutlineEditingStage.vue'
import TaskMemoryPanel from './components/TaskMemoryPanel.vue'

type UiPhase =
  | 'INPUT'
  | 'TITLE_GENERATING'
  | 'TITLE_SELECTING'
  | 'OUTLINE_GENERATING'
  | 'OUTLINE_EDITING'
  | 'CONTENT_GENERATING'
  | 'COMPLETED'

interface RealtimeLog {
  timestamp: number
  level: string
  message: string
}

interface TimelineLogViewModel {
  key: string
  title: string
  status: string
  duration: number
  time: string
  node?: string
  phase?: string
  message?: string
}

interface OutlineItem {
  section: number
  title: string
  points: string[]
}

interface TitleOptionViewModel {
  mainTitle: string
  subTitle: string
}

const TASK_STORAGE_KEY = 'article-create:active-task-id'

const router = useRouter()
const route = useRoute()
const loginUserStore = useLoginUserStore()

const isAdmin = computed(() => checkIsAdmin(loginUserStore.loginUser))
const isVip = computed(() => checkIsVip(loginUserStore.loginUser))
const quota = computed(() => loginUserStore.loginUser.quota ?? 0)
const hasQuota = computed(() => checkHasQuota(loginUserStore.loginUser))

const agentSteps = [
  { title: 'Title', description: 'Generate candidate titles from the topic' },
  { title: 'Outline', description: 'Plan the article structure and key points' },
  { title: 'Content', description: 'Stream the article draft into the preview area' },
  { title: 'Images', description: 'Analyze image requirements for the article' },
  { title: 'Render', description: 'Generate and upload article images' },
  { title: 'Merge', description: 'Merge text and images into the final article' },
]

const exampleTopics = [
  'How AI will change work in 2026',
  'How developers can improve competitiveness',
  'Pros and cons of remote work',
  'How to build deep thinking skills',
  'New energy vehicle trend analysis',
  'Healthy eating guide',
]

const currentPhase = ref<UiPhase>('INPUT')
const topic = ref('')
const selectedStyle = ref('')
const selectedImageMethods = ref<string[]>([])
const isCreating = ref(false)
const isCompleted = ref(false)
const isStreaming = ref(false)
const isOutlineStreaming = ref(false)
const currentStep = ref(0)
const taskId = ref('')
const errorVisible = ref(false)
const errorMessage = ref('')
const lastFailedTaskId = ref('')
const confirmLoading = ref(false)
const realtimeLogs = ref<RealtimeLog[]>([])
const executionStats = ref<API.AgentExecutionStats | null>(null)
const titleOptions = ref<TitleOptionViewModel[]>([])
const outline = ref<API.OutlineSection[]>([])
const outlineRaw = ref('')
const mainContentRef = ref<HTMLElement | null>(null)
const imageCount = ref(0)
const totalImages = ref(5)
const imageProgress = ref(0)
const article = ref<Partial<API.ArticleVO>>({
  mainTitle: '',
  subTitle: '',
  content: '',
  fullContent: '',
  images: [],
})

const parsedOutline = computed<OutlineItem[]>(() => {
  if (!outlineRaw.value) {
    return []
  }

  const raw = outlineRaw.value.trim()
  try {
    const parsed = JSON.parse(raw)
    if (parsed && Array.isArray(parsed.sections)) {
      return parsed.sections
    }
  } catch {
    try {
      const sectionsMatch = raw.match(/"sections"\s*:\s*\[/)
      if (!sectionsMatch) {
        return []
      }
      const sectionsStart = raw.indexOf('[', sectionsMatch.index)
      if (sectionsStart < 0) {
        return []
      }
      const afterStart = raw.substring(sectionsStart)
      const lastBrace = afterStart.lastIndexOf('}')
      if (lastBrace < 0) {
        return []
      }
      const partialArray = `${afterStart.substring(0, lastBrace + 1)}]`
      const parsed = JSON.parse(partialArray)
      if (Array.isArray(parsed)) {
        return parsed
      }
    } catch {
      return []
    }
  }
  return []
})

let eventSource: EventSource | null = null
let executionStatsTimer: ReturnType<typeof setInterval> | null = null

const hasExecutionObservability = computed(() => {
  return !!(
    executionStats.value &&
    ((executionStats.value.logs && executionStats.value.logs.length > 0) ||
      (executionStats.value.nodeLogs && executionStats.value.nodeLogs.length > 0))
  )
})

const agentLogs = computed(() => {
  const logs = executionStats.value?.logs ?? []
  return [...logs].sort(
    (a, b) => new Date(a.startTime || 0).getTime() - new Date(b.startTime || 0).getTime(),
  )
})

const nodeLogs = computed(() => {
  const logs = executionStats.value?.nodeLogs ?? []
  return [...logs].sort((a, b) => (a.timestamp ?? 0) - (b.timestamp ?? 0))
})

const latestNodeLog = computed(() => {
  return nodeLogs.value.length > 0 ? nodeLogs.value[nodeLogs.value.length - 1] : null
})

const nodeAverageDuration = computed(() => {
  const durationValues = Object.values(executionStats.value?.nodeDurations ?? {})
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value) && value >= 0)

  if (durationValues.length > 0) {
    return Math.round(
      durationValues.reduce((sum, value) => sum + value, 0) / durationValues.length,
    )
  }

  const elapsedValues = nodeLogs.value
    .map((log) => Number(log.elapsedMs ?? 0))
    .filter((value) => Number.isFinite(value) && value > 0)

  return elapsedValues.length > 0
    ? Math.round(elapsedValues.reduce((sum, value) => sum + value, 0) / elapsedValues.length)
    : 0
})

const recentNodeTimeline = computed<TimelineLogViewModel[]>(() => {
  return [...nodeLogs.value]
    .slice(-8)
    .reverse()
    .map((log, index) => ({
      key: `${log.node || 'node'}-${log.timestamp || index}`,
      title: getNodeDisplayName(log.node || ''),
      status: log.status || 'PENDING',
      duration: log.elapsedMs ?? 0,
      time: log.timestamp ? formatLogTime(log.timestamp) : '--',
      node: log.node || '',
      phase: getPhaseDisplayName(log.phase || ''),
      message: log.message || '',
    }))
})

const recentAgentTimeline = computed<TimelineLogViewModel[]>(() => {
  return [...agentLogs.value]
    .slice(-4)
    .reverse()
    .map((log, index) => ({
      key: `${log.id || log.agentName || 'agent'}-${index}`,
      title: getAgentDisplayName(log.agentName || ''),
      status: log.status || 'PENDING',
      duration: log.durationMs ?? 0,
      time: formatDateTime(log.startTime),
      message: log.errorMessage || '',
    }))
})

const markdownToHtml = (markdown: string | undefined) => marked.parse(markdown || '') as string

const formatDateTime = (value?: string) => {
  if (!value) {
    return '--'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

const scrollToBottom = () => {
  nextTick(() => {
    if (mainContentRef.value) {
      mainContentRef.value.scrollTop = mainContentRef.value.scrollHeight
    }
  })
}

const defaultArticle = (): Partial<API.ArticleVO> => ({
  mainTitle: '',
  subTitle: '',
  content: '',
  fullContent: '',
  images: [],
})

const payloadOf = (msg: SSEMessage) => msg.payload ?? {}

const normalizeTitleOptions = (items?: API.TitleOption[]) =>
  (items || []).map((item) => ({
    mainTitle: item.mainTitle || '',
    subTitle: item.subTitle || '',
  }))

const getStoredTaskId = () => {
  if (typeof window === 'undefined') {
    return ''
  }
  return window.localStorage.getItem(TASK_STORAGE_KEY) || ''
}

const persistTaskId = (value: string) => {
  if (typeof window === 'undefined') {
    return
  }
  window.localStorage.setItem(TASK_STORAGE_KEY, value)
}

const clearPersistedTaskId = () => {
  if (typeof window === 'undefined') {
    return
  }
  window.localStorage.removeItem(TASK_STORAGE_KEY)
}

const syncRouteTaskId = async (value?: string) => {
  const nextQuery = { ...route.query }
  if (value) {
    nextQuery.taskId = value
  } else {
    delete nextQuery.taskId
  }
  await router.replace({ query: nextQuery })
}

const rememberTask = async (value: string) => {
  taskId.value = value
  persistTaskId(value)
  await syncRouteTaskId(value)
}

const forgetTask = async () => {
  clearPersistedTaskId()
  await syncRouteTaskId(undefined)
}

const closeCurrentConnection = () => {
  closeSSE(eventSource)
  eventSource = null
}

const stopExecutionStatsPolling = () => {
  if (executionStatsTimer !== null) {
    clearInterval(executionStatsTimer)
    executionStatsTimer = null
  }
}

const clearActiveTaskSession = async () => {
  closeCurrentConnection()
  resetRuntimeState()
  taskId.value = ''
  await forgetTask()
}

const loadExecutionStats = async (activeTaskId: string, silent = true) => {
  if (!activeTaskId) {
    return
  }

  try {
    const res = await getExecutionLogs({ taskId: activeTaskId })
    executionStats.value = res.data.data || null
  } catch (error) {
    if (!silent) {
      console.error('Failed to load execution stats:', error)
    }
  }
}

const startExecutionStatsPolling = (activeTaskId: string) => {
  if (!activeTaskId) {
    return
  }

  stopExecutionStatsPolling()
  void loadExecutionStats(activeTaskId)
  executionStatsTimer = setInterval(() => {
    void loadExecutionStats(activeTaskId)
  }, 2000)
}

const resetRuntimeState = () => {
  stopExecutionStatsPolling()
  isCreating.value = false
  isCompleted.value = false
  isStreaming.value = false
  isOutlineStreaming.value = false
  currentStep.value = 0
  errorVisible.value = false
  errorMessage.value = ''
  lastFailedTaskId.value = ''
  confirmLoading.value = false
  titleOptions.value = []
  outline.value = []
  outlineRaw.value = ''
  realtimeLogs.value = []
  executionStats.value = null
  imageCount.value = 0
  totalImages.value = 5
  imageProgress.value = 0
  article.value = defaultArticle()
}

const resetCreate = async () => {
  await clearActiveTaskSession()
  currentPhase.value = 'INPUT'
  topic.value = ''
  selectedStyle.value = ''
  selectedImageMethods.value = []
}

const setUiByPhase = (phase?: string, status?: string, progress?: number) => {
  isCompleted.value = status === 'COMPLETED'

  if (status === 'COMPLETED') {
    currentPhase.value = 'COMPLETED'
    currentStep.value = 6
    isCreating.value = false
    isStreaming.value = false
    isOutlineStreaming.value = false
    return
  }

  switch (phase) {
    case 'TITLE_GENERATING':
      currentPhase.value = 'TITLE_GENERATING'
      currentStep.value = 0
      isCreating.value = true
      isStreaming.value = false
      isOutlineStreaming.value = false
      break
    case 'TITLE_SELECTING':
      currentPhase.value = 'TITLE_SELECTING'
      currentStep.value = 1
      isCreating.value = false
      isStreaming.value = false
      isOutlineStreaming.value = false
      break
    case 'OUTLINE_GENERATING':
      currentPhase.value = 'OUTLINE_GENERATING'
      currentStep.value = 1
      isCreating.value = true
      isStreaming.value = false
      isOutlineStreaming.value = true
      break
    case 'OUTLINE_EDITING':
      currentPhase.value = 'OUTLINE_EDITING'
      currentStep.value = 1
      isCreating.value = false
      isStreaming.value = false
      isOutlineStreaming.value = false
      break
    case 'CONTENT_GENERATING': {
      currentPhase.value = 'CONTENT_GENERATING'
      const resolvedProgress = progress ?? 3
      currentStep.value = resolvedProgress >= 6 ? 5 : Math.max(2, resolvedProgress - 1)
      isCreating.value = true
      isStreaming.value = resolvedProgress <= 3
      isOutlineStreaming.value = false
      break
    }
    default:
      currentPhase.value = 'INPUT'
      currentStep.value = 0
      isCreating.value = false
      isStreaming.value = false
      isOutlineStreaming.value = false
      break
  }
}

const applySnapshot = (snapshot: API.ArticleTaskSnapshotVO) => {
  topic.value = snapshot.topic || topic.value
  selectedStyle.value = snapshot.style || ''
  titleOptions.value = normalizeTitleOptions(snapshot.titleOptions)
  outline.value = snapshot.outline || []
  outlineRaw.value = snapshot.outlineRaw || (snapshot.outline ? JSON.stringify({ sections: snapshot.outline }) : '')
  article.value = {
    ...article.value,
    mainTitle: snapshot.title?.mainTitle || article.value.mainTitle || '',
    subTitle: snapshot.title?.subTitle || article.value.subTitle || '',
    content: snapshot.content || '',
    fullContent: snapshot.fullContent || '',
    images: snapshot.images || [],
  }

  const requirementCount = snapshot.imageRequirements?.length || 0
  const generatedCount = snapshot.images?.length || 0
  if (requirementCount > 0) {
    totalImages.value = requirementCount
  } else if (generatedCount > 0) {
    totalImages.value = generatedCount
  }
  imageCount.value = generatedCount
  imageProgress.value = totalImages.value > 0
    ? Math.min(100, Math.round((generatedCount / totalImages.value) * 100))
    : 0

  errorMessage.value = snapshot.errorMessage || ''
  errorVisible.value = snapshot.status === 'FAILED' && !!snapshot.errorMessage
  setUiByPhase(snapshot.phase, snapshot.status, snapshot.progress)
}

const shouldReconnectStream = (snapshot: API.ArticleTaskSnapshotVO) => {
  if (snapshot.status !== 'PROCESSING') {
    return false
  }
  return ['TITLE_GENERATING', 'OUTLINE_GENERATING', 'CONTENT_GENERATING'].includes(snapshot.phase || '')
}

const isTaskSnapshotMissing = (error: unknown) => {
  const code = (error as { response?: { data?: { code?: number } } })?.response?.data?.code
  return code === 40400
}

const restoreTask = async (restoreTaskId: string, silent = false) => {
  if (!restoreTaskId) {
    return
  }

  try {
    closeCurrentConnection()
    stopExecutionStatsPolling()
    const res = await getTaskSnapshot({ taskId: restoreTaskId })
    const snapshot = res.data.data
    if (!snapshot) {
      await clearActiveTaskSession()
      addLog(`Cleared invalid task reference: ${restoreTaskId}`, 'warning')
      return
    }

    await rememberTask(restoreTaskId)
    applySnapshot(snapshot)
    await loadExecutionStats(restoreTaskId)
    if (!['COMPLETED', 'FAILED'].includes(snapshot.status || '')) {
      startExecutionStatsPolling(restoreTaskId)
    }
    lastFailedTaskId.value = snapshot.status === 'FAILED' ? restoreTaskId : ''
    addLog(`Task restored: ${restoreTaskId}`, 'info')

    if (shouldReconnectStream(snapshot)) {
      connectToTaskStream(restoreTaskId, true)
    }

    if (!silent) {
      message.success('Previous task restored')
    }
  } catch (error) {
    console.error('Failed to restore task:', error)
    await clearActiveTaskSession()
    if (isTaskSnapshotMissing(error)) {
      addLog(`Stale task removed: ${restoreTaskId}`, 'warning')
    }
    if (!silent) {
      message.error(
        isTaskSnapshotMissing(error)
          ? 'Previous task no longer exists, please create a new one'
          : 'Failed to restore task, please create a new one',
      )
    }
  }
}

const connectToTaskStream = (activeTaskId: string, silent = false) => {
  closeCurrentConnection()
  eventSource = connectSSE(activeTaskId, {
    onMessage: handleSSEMessage,
    onError: handleSSEError,
    onComplete: handleSSEComplete,
  })
  if (!silent) {
    addLog('Realtime connection established', 'info')
  }
}

const startCreate = async () => {
  if (!topic.value.trim()) {
    message.warning('Please enter a topic')
    return
  }
  if (!hasQuota.value) {
    message.error('No quota left for creating an article')
    return
  }

  await clearActiveTaskSession()
  currentPhase.value = 'TITLE_GENERATING'
  isCreating.value = true
  addLog('Creating article task...', 'info')

  try {
    const res = await createArticle({
      topic: topic.value,
      style: selectedStyle.value || undefined,
      enabledImageMethods:
        selectedImageMethods.value.length > 0 ? selectedImageMethods.value : undefined,
    })
    const newTaskId = res.data.data
    if (!newTaskId) {
      throw new Error('Create task failed: task ID is missing')
    }

    await rememberTask(newTaskId)
    startExecutionStatsPolling(newTaskId)
    addLog(`Task created: ${newTaskId}`, 'success')
    await loginUserStore.fetchLoginUser()
    connectToTaskStream(newTaskId)
  } catch (error) {
    const err = error as Error
    message.error(err.message || 'Create task failed')
    isCreating.value = false
    currentPhase.value = 'INPUT'
  }
}

const addLog = (logMessage: string, level: string = 'info') => {
  realtimeLogs.value.push({
    timestamp: Date.now(),
    level,
    message: logMessage,
  })
  if (realtimeLogs.value.length > 50) {
    realtimeLogs.value.shift()
  }
}

const formatLogTime = (timestamp: number) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour12: false })
}

const canResumeCurrentTask = computed(() => {
  if (!taskId.value || isCreating.value || isCompleted.value) {
    return false
  }
  if (['INPUT', 'TITLE_SELECTING', 'OUTLINE_EDITING', 'COMPLETED'].includes(currentPhase.value)) {
    return false
  }
  return ['FAILED', 'PROCESSING', 'PENDING'].includes(articleStatusText.value.raw)
})

const articleStatusText = computed(() => {
  const snapshotStatus = executionStats.value?.overallStatus || ''
  const fallbackStatus = errorVisible.value ? 'FAILED' : isCompleted.value ? 'COMPLETED' : isCreating.value ? 'PROCESSING' : ''
  const raw = snapshotStatus || fallbackStatus
  return {
    raw,
    label: getStatusText(raw),
  }
})

const getStatusText = (status: string) => {
  const statusMap: Record<string, string> = {
    PENDING: '等待中',
    PROCESSING: '处理中',
    COMPLETED: '已完成',
    FAILED: '失败',
    SUCCESS: '成功',
    RUNNING: '执行中',
    INFO: '提示',
    NOT_FOUND: '暂无数据',
  }
  return statusMap[status] || status
}

const getNodeDisplayName = (node: string) => {
  const nodeMap: Record<string, string> = {
    workflow_phase_1: '工作流阶段一',
    workflow_phase_2: '工作流阶段二',
    workflow_phase_3: '工作流阶段三',
    workflow_error: '工作流异常',
    agent1_generate_titles: '生成标题',
    agent2_generate_outline: '生成大纲',
    agent3_generate_content: '生成正文',
    agent4_analyze_image_requirements: '分析配图需求',
    agent5_generate_images: '生成配图',
    agent6_merge_content: '图文合成',
    ai_modify_outline: 'AI 修改大纲',
  }
  return nodeMap[node] || node || '--'
}

const getPhaseDisplayName = (phase: string) => {
  const phaseMap: Record<string, string> = {
    TITLE_GENERATING: '标题生成',
    TITLE_SELECTING: '标题确认',
    OUTLINE_GENERATING: '大纲生成',
    OUTLINE_EDITING: '大纲编辑',
    CONTENT_GENERATING: '正文生成',
    PENDING: '等待中',
  }
  return phaseMap[phase] || phase || '未标记阶段'
}

const getAgentDisplayName = (agentName: string) => {
  const agentMap: Record<string, string> = {
    agent1_generate_titles: '生成标题',
    agent2_generate_outline: '生成大纲',
    agent3_generate_content: '生成正文',
    agent4_analyze_image_requirements: '分析配图需求',
    agent5_generate_images: '生成配图',
    agent6_merge_content: '图文合成',
    ai_modify_outline: 'AI 修改大纲',
  }
  return agentMap[agentName] || agentName || '--'
}

const handleSSEMessage = async (msg: SSEMessage) => {
  const payload = payloadOf(msg)
  if (msg.taskId) {
    taskId.value = msg.taskId
  }

  switch (msg.type) {
    case 'AGENT1_COMPLETE':
      currentPhase.value = 'TITLE_GENERATING'
      currentStep.value = 0
      isCreating.value = true
      addLog('Title generation completed', 'success')
      break
    case 'TITLES_GENERATED':
      currentPhase.value = 'TITLE_SELECTING'
      currentStep.value = 1
      isCreating.value = false
      titleOptions.value = normalizeTitleOptions(payload.titleOptions as API.TitleOption[])
      addLog(`Received ${titleOptions.value.length} title options`, 'success')
      break
    case 'AGENT2_STREAMING': {
      currentPhase.value = 'OUTLINE_GENERATING'
      currentStep.value = 1
      isCreating.value = true
      isOutlineStreaming.value = true
      const chunk = String(payload.content || '')
      outlineRaw.value += chunk
      scrollToBottom()
      break
    }
    case 'OUTLINE_GENERATED':
      currentPhase.value = 'OUTLINE_EDITING'
      currentStep.value = 1
      isCreating.value = false
      isOutlineStreaming.value = false
      outline.value = (payload.outline as API.OutlineSection[]) || []
      outlineRaw.value = JSON.stringify({ sections: outline.value })
      addLog('Outline generated, waiting for confirmation', 'success')
      break
    case 'AGENT2_COMPLETE':
      break
    case 'AGENT3_STREAMING': {
      currentPhase.value = 'CONTENT_GENERATING'
      currentStep.value = 2
      isCreating.value = true
      isStreaming.value = true
      const chunk = String(payload.content || '')
      article.value.content = `${article.value.content || ''}${chunk}`
      scrollToBottom()
      break
    }
    case 'AGENT3_COMPLETE':
      isStreaming.value = false
      currentStep.value = 3
      addLog('Content generated, analyzing image requirements', 'success')
      break
    case 'AGENT4_COMPLETE': {
      currentStep.value = 3
      const requirements = (payload.imageRequirements as API.ImageRequirement[]) || []
      totalImages.value = requirements.length > 0 ? requirements.length : totalImages.value
      addLog(`Image requirements ready: ${requirements.length}`, 'success')
      break
    }
    case 'IMAGE_COMPLETE':
      currentStep.value = 4
      imageCount.value += 1
      imageProgress.value = totalImages.value > 0
        ? Math.min(100, Math.round((imageCount.value / totalImages.value) * 100))
        : 0
      addLog(`Generating images ${imageCount.value}/${totalImages.value}`, 'info')
      break
    case 'AGENT5_COMPLETE':
      currentStep.value = 4
      article.value.images = (payload.images as API.ImageItem[]) || []
      if (article.value.images?.length) {
        imageCount.value = article.value.images.length
        totalImages.value = Math.max(totalImages.value, imageCount.value)
        imageProgress.value = 100
      }
      addLog('All images generated, merging article content', 'success')
      break
    case 'MERGE_COMPLETE':
      currentStep.value = 5
      article.value.fullContent = String(payload.fullContent || '')
      scrollToBottom()
      addLog('Content and images merged', 'success')
      break
    case 'ALL_COMPLETE':
      currentPhase.value = 'COMPLETED'
      currentStep.value = 6
      isCreating.value = false
      isCompleted.value = true
      isStreaming.value = false
      isOutlineStreaming.value = false
      lastFailedTaskId.value = ''
      await loadExecutionStats(taskId.value)
      stopExecutionStatsPolling()
      await forgetTask()
      message.success('Article created successfully')
      addLog('Article creation completed', 'success')
      break
    case 'ERROR': {
      const msgText = String(payload.message || 'Article creation failed')
      errorMessage.value = msgText
      errorVisible.value = true
      lastFailedTaskId.value = taskId.value
      isCreating.value = false
      isStreaming.value = false
      isOutlineStreaming.value = false
      if (msg.phase) {
        setUiByPhase(msg.phase, 'FAILED', msg.progress)
      }
      await loadExecutionStats(taskId.value)
      stopExecutionStatsPolling()
      await forgetTask()
      addLog(`Task failed: ${msgText}`, 'error')
      break
    }
    default:
      break
  }
}

const handleConfirmTitle = async (data: {
  mainTitle: string
  subTitle: string
  userDescription: string
}) => {
  confirmLoading.value = true
  try {
    await confirmTitle({
      taskId: taskId.value,
      selectedMainTitle: data.mainTitle,
      selectedSubTitle: data.subTitle,
      userDescription: data.userDescription,
    })
    article.value.mainTitle = data.mainTitle
    article.value.subTitle = data.subTitle
    currentPhase.value = 'OUTLINE_GENERATING'
    isCreating.value = true
    isOutlineStreaming.value = false
    startExecutionStatsPolling(taskId.value)
    connectToTaskStream(taskId.value, true)
    message.success('Title confirmed, generating outline')
  } catch (error) {
    const err = error as Error
    message.error(err.message || 'Confirm title failed')
  } finally {
    confirmLoading.value = false
  }
}

const handleConfirmOutline = async (outlineData: API.OutlineSection[]) => {
  confirmLoading.value = true
  try {
    await confirmOutline({
      taskId: taskId.value,
      outline: outlineData,
    })
    outline.value = outlineData
    outlineRaw.value = JSON.stringify({ sections: outlineData })
    currentPhase.value = 'CONTENT_GENERATING'
    currentStep.value = 2
    isCreating.value = true
    isStreaming.value = false
    startExecutionStatsPolling(taskId.value)
    connectToTaskStream(taskId.value, true)
    message.success('Outline confirmed, generating content')
  } catch (error) {
    const err = error as Error
    message.error(err.message || 'Confirm outline failed')
  } finally {
    confirmLoading.value = false
  }
}

const handleSSEError = (error: Event) => {
  console.error('SSE connection error:', error)
  closeCurrentConnection()
  stopExecutionStatsPolling()
  if (isCompleted.value || currentPhase.value === 'COMPLETED') {
    return
  }
  isCreating.value = false
  isStreaming.value = false
  isOutlineStreaming.value = false
  message.error('Realtime connection closed, please refresh and retry')
}

const handleSSEComplete = () => {
  closeCurrentConnection()
  if (currentPhase.value === 'COMPLETED' || errorVisible.value) {
    stopExecutionStatsPolling()
  }
}

const copyContent = async () => {
  const content = article.value.fullContent || article.value.content || ''
  try {
    await navigator.clipboard.writeText(content)
    message.success('Copied to clipboard')
  } catch {
    message.error('Copy failed')
  }
}

const viewArticle = () => {
  router.push(`/article/${taskId.value}`)
}

const handleResumeTask = async () => {
  if (!taskId.value) {
    message.warning('当前没有可恢复的任务')
    return
  }

  try {
    confirmLoading.value = true
    const activeTaskId = taskId.value
    const res = await resumeTask({ taskId: activeTaskId })
    const snapshot = res.data.data
    if (snapshot) {
      applySnapshot(snapshot)
    }
    errorVisible.value = false
    errorMessage.value = ''
    lastFailedTaskId.value = ''
    isCreating.value = true
    await rememberTask(activeTaskId)
    addLog(`Task resumed from ${snapshot?.phase || currentPhase.value}`, 'info')
    startExecutionStatsPolling(activeTaskId)
    connectToTaskStream(activeTaskId, true)
    message.success('已重新接续当前任务')
  } catch (error) {
    const err = error as Error
    message.error(err.message || '恢复任务失败')
  } finally {
    confirmLoading.value = false
  }
}

const handleRetryNode = async (node?: string) => {
  if (!taskId.value || !node) {
    message.warning('当前节点暂不支持重试')
    return
  }

  try {
    confirmLoading.value = true
    const activeTaskId = taskId.value
    const res = await retryNode({
      taskId: activeTaskId,
      node,
    })
    const snapshot = res.data.data
    if (snapshot) {
      applySnapshot(snapshot)
    }
    errorVisible.value = false
    errorMessage.value = ''
    lastFailedTaskId.value = ''
    isCreating.value = true
    await rememberTask(activeTaskId)
    addLog(`Retry node: ${getNodeDisplayName(node)}`, 'info')
    startExecutionStatsPolling(activeTaskId)
    connectToTaskStream(activeTaskId, true)
    message.success('已发起节点重试')
  } catch (error) {
    const err = error as Error
    message.error(err.message || '节点重试失败')
  } finally {
    confirmLoading.value = false
  }
}

onMounted(async () => {
  if (typeof route.query.topic === 'string') {
    topic.value = route.query.topic
  }

  const routeTaskId = typeof route.query.taskId === 'string' ? route.query.taskId : ''
  const cachedTaskId = getStoredTaskId()
  const restoreTaskId = routeTaskId || cachedTaskId
  if (restoreTaskId) {
    await restoreTask(restoreTaskId, true)
  }
})

onBeforeUnmount(() => {
  closeCurrentConnection()
  stopExecutionStatsPolling()
})
</script>

<style scoped lang="scss">
.article-create-page {
  height: calc(100vh - 64px);
  background: var(--color-background-secondary);
  overflow: hidden;
}

.create-layout {
  display: grid;
  grid-template-columns: 320px 1fr 300px;
  height: 100%;
}

/* 左侧边栏 */
.sidebar-left {
  background: white;
  border-right: 1px solid var(--color-border);
  padding: 24px;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.sidebar-header {
  margin-bottom: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--color-border-light);
}

.sidebar-title {
  font-size: 18px;
  font-weight: 700;
  margin: 0 0 4px;
  color: var(--color-text);
}

.sidebar-subtitle {
  font-size: 13px;
  color: var(--color-text-muted);
  margin: 0;
}

.flow-timeline {
  flex: 1;
}

.flow-item {
  display: flex;
  gap: 14px;
  padding: 14px 0;
  position: relative;

  &:not(:last-child)::before {
    content: '';
    position: absolute;
    left: 15px;
    top: 46px;
    bottom: -14px;
    width: 2px;
    background: var(--color-border);
  }

  &.completed::before {
    background: var(--color-primary);
  }

  &.active::before {
    background: linear-gradient(180deg, var(--color-primary) 50%, var(--color-border) 50%);
  }
}

.flow-indicator {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 14px;
  transition: all var(--transition-normal);

  .pending & {
    background: var(--color-background-tertiary);
    color: var(--color-text-muted);
    border: 2px solid var(--color-border);
  }

  .active & {
    background: rgba(34, 197, 94, 0.1);
    color: var(--color-primary);
    border: 2px solid var(--color-primary);
  }

  .completed & {
    background: var(--color-primary);
    color: white;
  }

  .step-number {
    font-weight: 600;
  }

  .spin-icon {
    animation: spin 1s linear infinite;
  }
}

.flow-content {
  flex: 1;
  min-width: 0;
}

.flow-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 2px;

  .pending & {
    color: var(--color-text-muted);
  }

  .active & {
    color: var(--color-primary-dark);
  }
}

.flow-desc {
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.4;
}

.flow-status {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--color-primary);
  font-weight: 500;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary);
  animation: pulse 1.5s infinite;
}


/* 主内容区 */
.main-content {
  padding: 32px 40px;
  overflow-y: auto;
  background: white;
}

/* 输入状态 */
.input-state {
  max-width: 700px;
  margin: 0 auto;
  padding-top: 60px;
}

.input-card {
  background: var(--color-background-secondary);
  border-radius: var(--radius-xl);
  padding: 40px;
}

.input-header {
  text-align: center;
  margin-bottom: 32px;
}

.input-title {
  font-size: 28px;
  font-weight: 700;
  margin: 0 0 8px;
  color: var(--color-text);
}

.input-subtitle {
  font-size: 15px;
  color: var(--color-text-secondary);
  margin: 0;
}

.input-area {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.topic-textarea {
  font-size: 15px;
  border-radius: var(--radius-lg);
  padding: 16px;
  background: white;

  &:focus {
    border-color: var(--color-primary);
    box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.1);
  }
}

.create-btn.ant-btn {
  height: 52px;
  font-size: 16px;
  font-weight: 600;
  border-radius: var(--radius-lg);
  background: var(--gradient-primary) !important;
  border: none !important;
  color: white !important;
  box-shadow: 0 4px 14px rgba(34, 197, 94, 0.3) !important;

  &:hover,
  &:focus,
  &:active {
    background: var(--gradient-primary) !important;
    color: white !important;
    border: none !important;
    box-shadow: 0 4px 14px rgba(34, 197, 94, 0.3) !important;
    opacity: 0.92;
  }

  &:disabled,
  &.ant-btn-disabled {
    background: var(--color-border) !important;
    box-shadow: none !important;
    opacity: 0.6;
    color: var(--color-text-muted) !important;
  }
}

.quota-warning {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 16px;
  background: rgba(255, 77, 79, 0.08);
  border: 1px solid rgba(255, 77, 79, 0.2);
  border-radius: var(--radius-md);
  color: #ff4d4f;
  font-size: 13px;
}

/* 文章风格选择 */
.style-section {
  padding: 16px;
  background: var(--color-background-secondary);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border-light);
}

.style-group {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.style-group :deep(.ant-radio-wrapper) {
  margin: 0;
  padding: 6px 12px;
  background: white;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: all 0.2s;
}

.style-group :deep(.ant-radio-wrapper:hover) {
  border-color: var(--color-primary);
  background: rgba(34, 197, 94, 0.04);
}

.style-group :deep(.ant-radio-wrapper-checked) {
  border-color: var(--color-primary);
  background: rgba(34, 197, 94, 0.08);
}

/* 配图方式选择 */
.image-methods-section {
  padding: 16px;
  background: var(--color-background-secondary);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border-light);
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
}

.section-tip {
  font-size: 12px;
  color: var(--color-text-muted);
}

.methods-group {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.methods-group :deep(.ant-checkbox-wrapper) {
  margin: 0;
  padding: 6px 12px;
  background: white;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: all 0.2s;
}

.methods-group :deep(.ant-checkbox-wrapper:hover) {
  border-color: var(--color-primary);
  background: rgba(34, 197, 94, 0.04);
}

.methods-group :deep(.ant-checkbox-wrapper-checked) {
  border-color: var(--color-primary);
  background: rgba(34, 197, 94, 0.08);
}

.methods-group :deep(.ant-checkbox-wrapper-disabled) {
  opacity: 0.6;
  cursor: not-allowed;
}

.vip-icon {
  color: var(--color-primary);
  font-size: 12px;
  margin-left: 4px;
}

.vip-notice {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding: 10px 14px;
  background: rgba(34, 197, 94, 0.08);
  border-radius: var(--radius-md);
  font-size: 12px;
  color: var(--color-primary-dark);
  border: 1px solid rgba(34, 197, 94, 0.2);

  .anticon {
    color: var(--color-primary);
  }

  .upgrade-link {
    color: var(--color-primary);
    font-weight: 600;
    text-decoration: none;

    &:hover {
      text-decoration: underline;
    }
  }
}

/* 创作进行中 */
.creating-state,
.completed-state {
  max-width: 100%;
}

/* 标题区域 */
.preview-header {
  text-align: center;
  margin-bottom: 24px;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--color-border-light);
}

.article-title {
  font-size: 28px;
  font-weight: 700;
  margin: 0 0 8px;
  color: var(--color-text);
  line-height: 1.4;
}

.article-subtitle {
  font-size: 16px;
  color: var(--color-text-secondary);
  margin: 0;
}

/* 大纲预览 */
.outline-preview {
  margin-bottom: 24px;
  padding: 20px 24px;
  background: var(--color-background-secondary);
  border-radius: var(--radius-lg);
}

.section-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-primary);
  margin-bottom: 16px;
}

.outline-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.outline-item {
  padding: 12px 16px;
  background: white;
  border-radius: var(--radius-md);
  border-left: 3px solid var(--color-primary);
}

.outline-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 8px;
}

.outline-points {
  margin: 0;
  padding-left: 18px;

  li {
    font-size: 13px;
    color: var(--color-text-secondary);
    line-height: 1.6;
    margin-bottom: 4px;

    &:last-child {
      margin-bottom: 0;
    }
  }
}

/* 正文预览 */
.content-preview {
  line-height: 1.8;
}

.markdown-body {
  line-height: 1.8;
  font-size: 15px;
  color: var(--color-text);

  :deep(h2) {
    font-size: 20px;
    font-weight: 600;
    margin: 24px 0 14px;
    padding-bottom: 10px;
    border-bottom: 1px solid var(--color-border);
    color: var(--color-text);
  }

  :deep(p) {
    margin-bottom: 14px;
    text-indent: 2em;
  }

  :deep(img) {
    display: block;
    max-width: 100%;
    max-height: 600px;
    width: auto;
    height: auto;
    margin: 20px auto;
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-md);
    object-fit: contain;
  }

  // Mermaid 图表特殊处理（SVG 格式）
  :deep(img[src$=".svg"]) {
    max-width: 800px;
    max-height: 500px;
  }
}

.typing-cursor {
  display: inline-block;
  animation: blink 1s infinite;
  color: var(--color-primary);
  font-weight: bold;
  font-size: 18px;
}

.image-progress-box {
  background: var(--color-background-secondary);
  border-radius: var(--radius-lg);
  padding: 24px;
  margin-top: 24px;
  text-align: center;

  .progress-header {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    margin-bottom: 16px;
    font-size: 15px;
    font-weight: 600;
    color: var(--color-text);
  }

  .progress-hint {
    margin: 12px 0 0;
    font-size: 13px;
    color: var(--color-text-muted);
  }
}

.loading-placeholder {
  text-align: center;
  padding: 100px 0;

  p {
    margin: 16px 0 0;
    color: var(--color-text-secondary);
    font-size: 15px;
  }
}

/* 完成状态 */
.success-header {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  background: var(--gradient-primary);
  border-radius: var(--radius-full);
  margin-bottom: 24px;
  color: white;
  font-size: 14px;
  font-weight: 600;

  .success-icon {
    font-size: 16px;
  }
}

/* 右侧辅助面板 */
.sidebar-right {
  background: white;
  border-left: 1px solid var(--color-border);
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  overflow-y: auto;
}

.panel-section {
  padding-bottom: 20px;
  border-bottom: 1px solid var(--color-border-light);

  &:last-of-type {
    border-bottom: none;
    padding-bottom: 0;
  }
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0 0 16px;
}

/* 配额信息样式 */
.quota-section {
  background: linear-gradient(135deg, rgba(34, 197, 94, 0.05) 0%, rgba(34, 197, 94, 0.02) 100%);
  border-radius: var(--radius-lg);
  padding: 16px !important;
  margin: -8px -8px 12px -8px;
}

.quota-admin {
  display: flex;
  align-items: center;
  gap: 10px;
}

.quota-badge {
  padding: 4px 10px;
  border-radius: var(--radius-full);
  font-size: 12px;
  font-weight: 600;

  &.admin {
    background: linear-gradient(135deg, #0F172A 0%, #1E293B 100%);
    color: white;
  }

  &.vip {
    background: var(--gradient-primary);
    color: white;
  }
}

.quota-text {
  font-size: 14px;
  color: var(--color-text-secondary);
}

.quota-info {
  text-align: center;
}

.quota-display {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 4px;
}

.quota-number {
  font-size: 36px;
  font-weight: 700;
  color: var(--color-primary);
  line-height: 1;

  &.low {
    color: #faad14;
  }

  &.empty {
    color: #ff4d4f;
  }
}

.quota-unit {
  font-size: 14px;
  color: var(--color-text-muted);
}

.quota-label {
  font-size: 12px;
  color: var(--color-text-muted);
  margin: 4px 0 12px;
}

.quota-progress {
  max-width: 120px;
  margin: 0 auto;
}

/* 热门选题 */
.hot-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hot-tag {
  display: inline-block;
  padding: 8px 12px;
  background: var(--color-background-secondary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: 12px;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
    background: rgba(34, 197, 94, 0.05);
    transform: translateY(-1px);
  }
}

/* 创作技巧 */
.tips-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tip-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  background: var(--color-background-secondary);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);

  &:hover {
    background: rgba(34, 197, 94, 0.05);
  }
}

.tip-icon {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--gradient-primary);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.tip-content {
  flex: 1;
  min-width: 0;
}

.tip-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 2px;
}

.tip-desc {
  font-size: 11px;
  color: var(--color-text-muted);
  line-height: 1.4;
}

/* 创作进度信息 */
.progress-info {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}

.progress-step {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  background: var(--color-background-secondary);
  border-radius: var(--radius-md);
}

.step-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

.step-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-primary);
}

.progress-tip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px;
  background: rgba(34, 197, 94, 0.08);
  border-radius: var(--radius-md);
  font-size: 12px;
  color: var(--color-primary-dark);
  line-height: 1.5;

  .anticon {
    flex-shrink: 0;
    margin-top: 2px;
  }

  &.waiting {
    background: rgba(250, 173, 20, 0.08);
    color: #d48806;
  }
}

.resume-section {
  .resume-card {
    padding: 14px;
    border-radius: var(--radius-lg);
    background: linear-gradient(135deg, rgba(250, 173, 20, 0.10) 0%, rgba(250, 173, 20, 0.04) 100%);
    border: 1px solid rgba(250, 173, 20, 0.18);
  }

  .resume-title {
    font-size: 13px;
    font-weight: 600;
    color: #ad6800;
    margin-bottom: 8px;
  }

  .resume-desc {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 12px;
    font-size: 12px;
    color: var(--color-text-secondary);
    line-height: 1.5;
  }

  .resume-btn.ant-btn {
    background: linear-gradient(135deg, #faad14 0%, #d48806 100%) !important;
    border: none !important;
    color: white !important;

    &:hover,
    &:focus {
      opacity: 0.92;
      color: white !important;
    }
  }
}

/* 执行观测 */
.observability-section {
  .observability-summary {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
    margin-bottom: 16px;
  }

  .summary-card {
    padding: 12px;
    border-radius: var(--radius-md);
    background: linear-gradient(135deg, rgba(34, 197, 94, 0.08) 0%, rgba(34, 197, 94, 0.03) 100%);
    border: 1px solid rgba(34, 197, 94, 0.12);
  }

  .summary-label {
    display: block;
    margin-bottom: 4px;
    font-size: 11px;
    color: var(--color-text-muted);
  }

  .summary-value {
    display: block;
    font-size: 13px;
    font-weight: 600;
    color: var(--color-primary-dark);
    line-height: 1.5;
  }

  .observability-group + .observability-group {
    margin-top: 16px;
  }

  .observability-group-header {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 10px;
  }

  .observability-group-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text);
  }

  .observability-group-subtitle {
    font-size: 11px;
    color: var(--color-text-muted);
    line-height: 1.5;
  }

  .mini-timeline {
    position: relative;
    padding-left: 4px;

    &::before {
      content: '';
      position: absolute;
      left: 10px;
      top: 8px;
      bottom: 8px;
      width: 2px;
      background: var(--color-border-light);
    }

    &.compact .mini-timeline-item {
      padding-bottom: 10px;
    }
  }

  .mini-timeline-item {
    position: relative;
    display: flex;
    gap: 10px;
    padding-left: 24px;
    padding-bottom: 14px;

    &:last-child {
      padding-bottom: 0;
    }
  }

  .mini-timeline-indicator {
    position: absolute;
    left: 0;
    top: 2px;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: white;
    border: 2px solid var(--color-border);
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .mini-timeline-item.success .mini-timeline-indicator {
    border-color: var(--color-success);
  }

  .mini-timeline-item.failed .mini-timeline-indicator {
    border-color: var(--color-error);
  }

  .mini-timeline-item.running .mini-timeline-indicator {
    border-color: var(--color-primary);
  }

  .mini-timeline-item.info .mini-timeline-indicator {
    border-color: #1677ff;
  }

  .timeline-icon {
    font-size: 12px;

    &.success {
      color: var(--color-success);
    }

    &.failed {
      color: var(--color-error);
    }

    &.running {
      color: var(--color-primary);
    }

    &.info {
      color: #1677ff;
    }
  }

  .mini-timeline-content {
    flex: 1;
    min-width: 0;
    padding: 10px 12px;
    border-radius: var(--radius-md);
    background: var(--color-background-secondary);
    border: 1px solid var(--color-border-light);
  }

  .mini-timeline-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 8px;
    margin-bottom: 4px;
  }

  .mini-timeline-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text);
  }

  .mini-timeline-duration {
    flex-shrink: 0;
    font-size: 12px;
    font-weight: 600;
    color: var(--color-primary);
  }

  .mini-timeline-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    font-size: 11px;
    color: var(--color-text-muted);
  }

  .timeline-phase-tag {
    display: inline-flex;
    align-items: center;
    padding: 2px 8px;
    border-radius: var(--radius-full);
    background: white;
    border: 1px solid var(--color-border-light);
    color: var(--color-text-secondary);
  }

  .mini-timeline-message {
    margin-top: 8px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--color-text-secondary);
    word-break: break-word;
    white-space: pre-wrap;

    &.error {
      color: var(--color-error);
    }
  }

  .retry-node-btn.ant-btn-link {
    padding: 0;
    margin-top: 8px;
    height: auto;
    color: #d48806;
    font-size: 12px;
    font-weight: 600;
  }
}

/* 实时日志 */
.memory-section {
  :deep(.task-memory-panel) {
    gap: 14px;
  }
}

.realtime-logs-section {
  .logs-container {
    max-height: 300px;
    overflow-y: auto;
    background: var(--color-background);
    border-radius: var(--radius-md);
    border: 1px solid var(--color-border-light);
    padding: 8px;

    .log-entry {
      display: flex;
      gap: 8px;
      padding: 6px 8px;
      font-size: 11px;
      line-height: 1.4;
      border-radius: var(--radius-sm);
      margin-bottom: 4px;
      transition: background var(--transition-fast);

      &:hover {
        background: var(--color-background-secondary);
      }

      &.success {
        .log-time {
          color: var(--color-success);
        }
      }

      &.error {
        background: rgba(239, 68, 68, 0.05);
        .log-time {
          color: var(--color-error);
        }
        .log-message {
          color: var(--color-error);
        }
      }

      .log-time {
        flex-shrink: 0;
        color: var(--color-text-muted);
        font-weight: 500;
      }

      .log-message {
        flex: 1;
        color: var(--color-text-secondary);
      }
    }

    &::-webkit-scrollbar {
      width: 6px;
    }

    &::-webkit-scrollbar-thumb {
      background: var(--color-border);
      border-radius: var(--radius-full);
    }

    &::-webkit-scrollbar-track {
      background: transparent;
    }
  }
}

/* 选题展示 */
.topic-display {
  padding: 12px 16px;
  background: var(--color-background-secondary);
  border-radius: var(--radius-md);
  border-left: 3px solid var(--color-primary);

  p {
    margin: 0;
    font-size: 13px;
    color: var(--color-text);
    line-height: 1.6;
  }
}

/* 提示面板样式 */
.tips-section {
  .tip-icon {
    background: transparent;
    font-size: 16px;
  }

  .tip-desc {
    font-size: 12px;
  }
}

/* 文章统计 */
.stats-section {
  margin-top: auto;
}

.stats-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.stat-item {
  text-align: center;
  padding: 16px 12px;
  background: var(--color-background-secondary);
  border-radius: var(--radius-md);
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-primary);
  margin-bottom: 4px;
}

.stat-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

/* 底部帮助链接 */
.panel-footer {
  margin-top: auto;
  padding-top: 16px;
  border-top: 1px solid var(--color-border-light);
  display: flex;
  justify-content: center;
  gap: 20px;
}

.help-link {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-text-muted);
  cursor: pointer;
  transition: color var(--transition-fast);

  &:hover {
    color: var(--color-primary);
  }
}

.action-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.action-btn {
  height: 40px;
  font-size: 13px;
  font-weight: 500;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;

  &.primary {
    background: var(--gradient-primary);
    border: none;
    color: white;

    &:hover {
      opacity: 0.9;
    }
  }
}

/* 阶段切换过渡动画 */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.3s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateX(30px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateX(-30px);
}

/* 动画 */
@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

/* 加载阶段样式 */
.loading-stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 120px 40px;
  text-align: center;

  h3 {
    font-size: 20px;
    font-weight: 600;
    color: var(--color-text);
    margin: 24px 0 8px;
  }

  p {
    font-size: 14px;
    color: var(--color-text-secondary);
    margin: 0;
  }
}

/* 大纲生成中状态 */
.outline-generating-state {
  max-width: 100%;
}

.outline-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px 20px;
  font-size: 14px;
  color: var(--color-text-secondary);
}

/* 渐入动画 */
@keyframes fade-in {
  from {
    opacity: 0;
    transform: translateY(-10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.fade-in {
  animation: fade-in 0.4s ease-out;
}

/* 响应式 */
@media (max-width: 1400px) {
  .create-layout {
    grid-template-columns: 280px 1fr 260px;
  }
}

@media (max-width: 1200px) {
  .create-layout {
    grid-template-columns: 240px 1fr 220px;
  }
}

@media (max-width: 992px) {
  .article-create-page {
    height: auto;
    min-height: calc(100vh - 64px);
    overflow: visible;
  }

  .create-layout {
    grid-template-columns: 1fr;
    height: auto;
  }

  .sidebar-left,
  .sidebar-right {
    display: none;
  }

  .main-content {
    padding: 20px;
  }
}

@media (max-width: 768px) {
  .observability-section {
    .observability-summary {
      grid-template-columns: 1fr;
    }

    .mini-timeline-header {
      flex-direction: column;
      align-items: flex-start;
    }
  }
}
</style>
