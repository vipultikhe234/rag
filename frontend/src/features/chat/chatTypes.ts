import type { ChatMessage } from '../../api/types'

/**
 * searching: waiting for retrieval · thinking: sources known, waiting for the first token ·
 * streaming: tokens arriving · done · stopped: cancelled by the user · failed: error, nothing stored
 */
export type MessageStatus = 'searching' | 'thinking' | 'streaming' | 'done' | 'stopped' | 'failed'

export interface DisplayMessage extends ChatMessage {
  status: MessageStatus
  /** Question that produced this answer; used by "Retry". */
  question?: string
  error?: string
  /** Not stored on the server; removed when the next question is sent. */
  discard?: boolean
}

export const MAX_QUESTION_LENGTH = 1000

export function fromServer(message: ChatMessage): DisplayMessage {
  return { ...message, status: 'done' }
}
