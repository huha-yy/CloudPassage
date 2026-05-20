/**
 * SSE helpers for normalized article events.
 */

export interface SSEMessage {
  taskId?: string
  type: string
  phase?: string
  progress?: number
  timestamp?: number
  payload?: Record<string, any>
}

export interface SSEOptions {
  onMessage: (message: SSEMessage) => void
  onError?: (error: Event) => void
  onComplete?: () => void
}

export const connectSSE = (taskId: string, options: SSEOptions): EventSource => {
  const { onMessage, onError, onComplete } = options
  const eventSource = new EventSource(`/api/article/progress/${taskId}`)

  eventSource.onmessage = (event) => {
    try {
      const message: SSEMessage = JSON.parse(event.data)
      onMessage(message)
      if (message.type === 'ALL_COMPLETE' || message.type === 'ERROR') {
        eventSource.close()
        onComplete?.()
      }
    } catch (error) {
      console.error('Failed to parse SSE message:', error)
    }
  }

  eventSource.onerror = (error) => {
    console.error('SSE connection error:', error)
    onError?.(error)
    eventSource.close()
  }

  return eventSource
}

export const closeSSE = (eventSource: EventSource | null) => {
  if (eventSource) {
    eventSource.close()
  }
}
