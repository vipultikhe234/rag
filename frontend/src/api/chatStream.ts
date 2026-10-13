import { UNAUTHORIZED_EVENT } from './client'
import { clearSession, getToken } from './tokenStorage'
import type { ApiErrorBody, ChatAnswer, RetrievalInfo, SourceReference } from './types'

export interface ChatStreamMeta {
  conversationId: string
  sources: SourceReference[]
  retrieval: RetrievalInfo | null
}

export interface ChatStreamHandlers {
  onMeta: (meta: ChatStreamMeta) => void
  onDelta: (text: string) => void
  signal: AbortSignal
}

/** Failure reported by the stream endpoint, either as a JSON error response or as an `error` event. */
export class ChatStreamError extends Error {
  readonly status: number | null
  readonly code: string

  constructor(status: number | null, code: string, message: string) {
    super(message)
    this.name = 'ChatStreamError'
    this.status = status
    this.code = code
  }
}

const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')

/**
 * POSTs a question to /chat/stream and dispatches the server-sent events (`meta`, `delta`, then `done` or
 * `error`). Resolves with the stored answer; rejects with ChatStreamError, or an AbortError when stopped.
 */
export async function streamChat(
  question: string,
  conversationId: string | null,
  { onMeta, onDelta, signal }: ChatStreamHandlers,
): Promise<ChatAnswer> {
  const token = getToken()
  const response = await fetch(`${baseUrl}/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream, application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ question, conversationId }),
    signal,
  })

  if (!response.ok || !response.body) {
    throw await responseError(response)
  }

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += value.replace(/\r\n?/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary !== -1) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const result = handleEvent(block, onMeta, onDelta)
        if (result) return result
        boundary = buffer.indexOf('\n\n')
      }
    }
  } finally {
    reader.releaseLock()
  }
  throw new ChatStreamError(null, 'STREAM_INTERRUPTED', 'The connection was interrupted before the answer finished.')
}

function handleEvent(
  block: string,
  onMeta: ChatStreamHandlers['onMeta'],
  onDelta: ChatStreamHandlers['onDelta'],
): ChatAnswer | null {
  let event = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  if (data.length === 0) return null
  const payload: unknown = JSON.parse(data.join('\n'))
  switch (event) {
    case 'meta':
      onMeta(payload as ChatStreamMeta)
      return null
    case 'delta':
      onDelta((payload as { text: string }).text)
      return null
    case 'done':
      return payload as ChatAnswer
    case 'error': {
      const { code, message } = payload as { code: string; message: string }
      throw new ChatStreamError(null, code, message)
    }
    default:
      return null
  }
}

async function responseError(response: Response): Promise<ChatStreamError> {
  if (response.status === 401) {
    clearSession()
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  }
  let body: ApiErrorBody = {}
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // Not JSON: e.g. the dev proxy reporting that the backend is down.
  }
  if (!body.code && response.status >= 500) {
    return new ChatStreamError(response.status, 'BACKEND_UNREACHABLE', '')
  }
  if (response.status === 404 && body.code !== 'CONVERSATION_NOT_FOUND') {
    // A backend built before /chat/stream existed answers with a generic 404.
    return new ChatStreamError(response.status, 'STREAM_ENDPOINT_MISSING', '')
  }
  return new ChatStreamError(response.status, body.code ?? `HTTP_${response.status}`, body.message ?? '')
}
