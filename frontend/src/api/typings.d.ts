declare namespace API {
  type AgentExecutionStats = {
    taskId?: string
    totalDurationMs?: number
    agentCount?: number
    agentDurations?: Record<string, any>
    nodeCount?: number
    nodeDurations?: Record<string, any>
    overallStatus?: string
    logs?: AgentLog[]
    nodeLogs?: NodeExecutionLogVO[]
  }

  type AgentLog = {
    id?: number
    taskId?: string
    agentName?: string
    startTime?: string
    endTime?: string
    durationMs?: number
    status?: string
    errorMessage?: string
    prompt?: string
    inputData?: string
    outputData?: string
    createTime?: string
    updateTime?: string
    isDelete?: number
  }

  type NodeExecutionLogVO = {
    taskId?: string
    phase?: string
    node?: string
    status?: string
    message?: string
    elapsedMs?: number
    timestamp?: number
    promptKey?: string
    promptVersion?: string
    model?: string
    temperature?: number
    maxTokens?: number
    topP?: number
    decisionSource?: string
    decisionReason?: string
    decisionSummary?: string
    memoryContextSummary?: string
    memoryContextSnapshot?: string
    fallbackSource?: string
    fallbackReason?: string
    fallbackSummary?: string
  }

  type NodeReplaySnapshotVO = {
    snapshotId?: string
    snapshotVersion?: string
    taskId?: string
    phase?: string
    node?: string
    status?: string
    message?: string
    startedAt?: number
    finishedAt?: number
    elapsedMs?: number
    promptKey?: string
    promptVersion?: string
    model?: string
    temperature?: number
    maxTokens?: number
    topP?: number
    decisionSource?: string
    decisionReason?: string
    decisionSummary?: string
    memoryContextSummary?: string
    memoryContextSnapshot?: string
    fallbackSource?: string
    fallbackReason?: string
    fallbackSummary?: string
    inputSummary?: string
    outputSummary?: string
    errorMessage?: string
    retryCount?: number
    replayable?: boolean
  }

  type ArticleAiModifyOutlineRequest = {
    taskId?: string
    modifySuggestion?: string
  }

  type ArticleConfirmOutlineRequest = {
    taskId?: string
    outline?: OutlineSection[]
  }

  type ArticleConfirmTitleRequest = {
    taskId?: string
    selectedMainTitle?: string
    selectedSubTitle?: string
    userDescription?: string
  }

  type ArticleCreateRequest = {
    topic?: string
    style?: string
    enabledImageMethods?: string[]
  }

  type ArticleResumeRequest = {
    taskId?: string
  }

  type ArticleRetryNodeRequest = {
    taskId?: string
    node?: string
  }

  type ArticleQueryRequest = {
    pageNum?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    userId?: number
    status?: string
  }

  type ArticleTaskSnapshotVO = {
    taskId?: string
    topic?: string
    style?: string
    userDescription?: string
    status?: string
    phase?: string
    progress?: number
    errorMessage?: string
    titleOptions?: TitleOption[]
    title?: TitleOption
    outline?: OutlineSection[]
    outlineRaw?: string
    content?: string
    fullContent?: string
    enabledImageMethods?: string[]
    imageRequirements?: ImageRequirement[]
    images?: ImageItem[]
    updatedAt?: number
  }

  type ArticleTaskMemoryVO = {
    taskId?: string
    userId?: number
    topic?: string
    style?: string
    userDescription?: string
    currentPhase?: string
    lastSuccessNode?: string
    lastFailedNode?: string
    retryCount?: number
    recentErrorMessage?: string
    selectedTitle?: TitleOption
    outlineSummary?: MemorySummaryVO
    contentSummary?: MemorySummaryVO
    contentReview?: MemoryContentReviewVO
    imageStrategy?: MemoryImageStrategyVO
    qualitySignals?: MemorySignalVO[]
    manualActions?: MemoryActionVO[]
    nodeSnapshots?: NodeSnapshotVO[]
    updatedAt?: number
  }

  type UserCreationPreferenceVO = {
    userId?: number
    preferredStyle?: string
    preferredImageMethods?: string[]
    styleUsage?: Record<string, any>
    imageMethodUsage?: Record<string, any>
    recentFailureTags?: string[]
    completedTaskCount?: number
    updatedAt?: number
  }

  type MemorySummaryVO = {
    text?: string
    sourceCount?: number
    highlights?: string[]
    sourceType?: string
  }

  type MemoryImageStrategyVO = {
    methods?: string[]
    needImages?: boolean
    decisionReason?: string
    decisionSource?: string
    requirementCount?: number
    generatedCount?: number
    sources?: string[]
    fallbackCount?: number
    fallbackSummary?: string
  }

  type MemoryContentReviewVO = {
    needsRevision?: boolean
    revised?: boolean
    issueCount?: number
    summary?: string
    issues?: string[]
    qualitySignals?: string[]
  }

  type MemorySignalVO = {
    code?: string
    label?: string
    detail?: string
    phase?: string
    node?: string
    timestamp?: number
  }

  type MemoryActionVO = {
    type?: string
    label?: string
    detail?: string
    phase?: string
    node?: string
    timestamp?: number
  }

  type NodeSnapshotVO = {
    node?: string
    phase?: string
    label?: string
    status?: string
    summary?: string
    detail?: string
    highlights?: string[]
    timestamp?: number
  }

  type ArticleVO = {
    id?: number
    taskId?: string
    userId?: number
    topic?: string
    userDescription?: string
    mainTitle?: string
    subTitle?: string
    titleOptions?: TitleOption[]
    outline?: OutlineItem[]
    content?: string
    fullContent?: string
    coverImage?: string
    images?: ImageItem[]
    status?: string
    phase?: string
    errorMessage?: string
    createTime?: string
    completedTime?: string
    updateTime?: string
  }

  type BaseResponseAgentExecutionStats = {
    code?: number
    data?: AgentExecutionStats
    message?: string
  }

  type BaseResponseArticleTaskSnapshotVO = {
    code?: number
    data?: ArticleTaskSnapshotVO
    message?: string
  }

  type BaseResponseArticleTaskMemoryVO = {
    code?: number
    data?: ArticleTaskMemoryVO
    message?: string
  }

  type BaseResponseArticleVO = {
    code?: number
    data?: ArticleVO
    message?: string
  }

  type BaseResponseUserCreationPreferenceVO = {
    code?: number
    data?: UserCreationPreferenceVO
    message?: string
  }

  type BaseResponseBoolean = {
    code?: number
    data?: boolean
    message?: string
  }

  type BaseResponseListOutlineSection = {
    code?: number
    data?: OutlineSection[]
    message?: string
  }

  type BaseResponseListNodeReplaySnapshotVO = {
    code?: number
    data?: NodeReplaySnapshotVO[]
    message?: string
  }

  type BaseResponseListPaymentRecord = {
    code?: number
    data?: PaymentRecord[]
    message?: string
  }

  type BaseResponseLoginUserVO = {
    code?: number
    data?: LoginUserVO
    message?: string
  }

  type BaseResponseLong = {
    code?: number
    data?: number
    message?: string
  }

  type BaseResponsePageArticleVO = {
    code?: number
    data?: PageArticleVO
    message?: string
  }

  type BaseResponsePageUserVO = {
    code?: number
    data?: PageUserVO
    message?: string
  }

  type BaseResponseStatisticsVO = {
    code?: number
    data?: StatisticsVO
    message?: string
  }

  type BaseResponseString = {
    code?: number
    data?: string
    message?: string
  }

  type BaseResponseUser = {
    code?: number
    data?: User
    message?: string
  }

  type BaseResponseUserVO = {
    code?: number
    data?: UserVO
    message?: string
  }

  type BaseResponseVoid = {
    code?: number
    data?: Record<string, any>
    message?: string
  }

  type DeleteRequest = {
    id?: number
  }

  type getArticleParams = {
    taskId: string
  }

  type getExecutionLogsParams = {
    taskId: string
  }

  type getProgressParams = {
    taskId: string
  }

  type getTaskSnapshotParams = {
    taskId: string
  }

  type getTaskMemoryParams = {
    taskId: string
  }

  type getNodeReplaySnapshotsParams = {
    taskId: string
  }

  type getUserByIdParams = {
    id: number
  }

  type getUserVOByIdParams = {
    id: number
  }

  type ImageItem = {
    position?: number
    url?: string
    method?: string
    keywords?: string
    sectionTitle?: string
    description?: string
    placeholderId?: string
    requestedMethod?: string
    fallbackApplied?: boolean
    fallbackReason?: string
    attemptedMethods?: string[]
  }

  type ImageRequirement = {
    position?: number
    type?: string
    sectionTitle?: string
    keywords?: string
    imageSource?: string
    prompt?: string
    placeholderId?: string
  }

  type LoginUserVO = {
    id?: number
    userAccount?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    quota?: number
    vipTime?: string
    createTime?: string
    updateTime?: string
  }

  type OutlineItem = {
    section?: number
    title?: string
    points?: string[]
  }

  type OutlineSection = {
    section?: number
    title?: string
    points?: string[]
  }

  type PageArticleVO = {
    records?: ArticleVO[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type PageUserVO = {
    records?: UserVO[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type PaymentRecord = {
    id?: number
    userId?: number
    stripeSessionId?: string
    stripePaymentIntentId?: string
    amount?: number
    currency?: string
    status?: string
    productType?: string
    description?: string
    refundTime?: string
    refundReason?: string
    createTime?: string
    updateTime?: string
  }

  type refundParams = {
    reason?: string
  }

  type SseEmitter = {
    timeout?: number
  }

  type StatisticsVO = {
    todayCount?: number
    weekCount?: number
    monthCount?: number
    totalCount?: number
    successRate?: number
    avgDurationMs?: number
    activeUserCount?: number
    totalUserCount?: number
    vipUserCount?: number
    quotaUsed?: number
  }

  type TitleOption = {
    mainTitle?: string
    subTitle?: string
  }

  type User = {
    id?: number
    userAccount?: string
    userPassword?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    quota?: number
    vipTime?: string
    editTime?: string
    createTime?: string
    updateTime?: string
    isDelete?: number
  }

  type UserAddRequest = {
    userName?: string
    userAccount?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
  }

  type UserLoginRequest = {
    userAccount?: string
    userPassword?: string
  }

  type UserQueryRequest = {
    pageNum?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    id?: number
    userName?: string
    userAccount?: string
    userProfile?: string
    userRole?: string
  }

  type UserRegisterRequest = {
    userAccount?: string
    userPassword?: string
    checkPassword?: string
  }

  type UserUpdateRequest = {
    id?: number
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
  }

  type UserVO = {
    id?: number
    userAccount?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    createTime?: string
  }
}
