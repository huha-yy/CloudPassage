// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 获取文章详情 GET /article/${param0} */
export async function getArticle(
  params: API.getArticleParams,
  options?: { [key: string]: any }
) {
  const { taskId: param0, ...queryParams } = params
  return request<API.BaseResponseArticleVO>(`/article/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 获取任务快照 GET /article/snapshot/${param0} */
export async function getTaskSnapshot(
  params: API.getTaskSnapshotParams,
  options?: { [key: string]: any }
) {
  const { taskId: param0, ...queryParams } = params
  return request<API.BaseResponseArticleTaskSnapshotVO>(`/article/snapshot/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 获取任务级记忆 GET /article/task-memory/${param0} */
export async function getTaskMemory(
  params: API.getTaskMemoryParams,
  options?: { [key: string]: any }
) {
  const { taskId: param0, ...queryParams } = params
  return request<API.BaseResponseArticleTaskMemoryVO>(`/article/task-memory/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 获取节点回放快照 GET /article/node-replay/${param0} */
export async function getNodeReplaySnapshots(
  params: API.getNodeReplaySnapshotsParams,
  options?: { [key: string]: any }
) {
  const { taskId: param0, ...queryParams } = params
  return request<API.BaseResponseListNodeReplaySnapshotVO>(`/article/node-replay/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** AI 修改大纲 POST /article/ai-modify-outline */
export async function aiModifyOutline(
  body: API.ArticleAiModifyOutlineRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseListOutlineSection>('/article/ai-modify-outline', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 确认大纲 POST /article/confirm-outline */
export async function confirmOutline(
  body: API.ArticleConfirmOutlineRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseVoid>('/article/confirm-outline', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 确认标题 POST /article/confirm-title */
export async function confirmTitle(
  body: API.ArticleConfirmTitleRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseVoid>('/article/confirm-title', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 创建文章任务 POST /article/create */
export async function createArticle(
  body: API.ArticleCreateRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseString>('/article/create', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 恢复或重试任务 POST /article/resume */
export async function resumeTask(
  body: API.ArticleResumeRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseArticleTaskSnapshotVO>('/article/resume', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 重试失败节点 POST /article/retry-node */
export async function retryNode(
  body: API.ArticleRetryNodeRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseArticleTaskSnapshotVO>('/article/retry-node', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 删除文章 POST /article/delete */
export async function deleteArticle(body: API.DeleteRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/article/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 获取任务执行日志 GET /article/execution-logs/${param0} */
export async function getExecutionLogs(
  params: API.getExecutionLogsParams,
  options?: { [key: string]: any }
) {
  const { taskId: param0, ...queryParams } = params
  return request<API.BaseResponseAgentExecutionStats>(`/article/execution-logs/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 分页查询文章列表 POST /article/list */
export async function listArticle(body: API.ArticleQueryRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponsePageArticleVO>('/article/list', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 获取文章生成进度(SSE) GET /article/progress/${param0} */
export async function getProgress(
  params: API.getProgressParams,
  options?: { [key: string]: any }
) {
  const { taskId: param0, ...queryParams } = params
  return request<API.SseEmitter>(`/article/progress/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}
