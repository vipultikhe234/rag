import axios from 'axios'
import { ChatStreamError } from './chatStream'
import type { ApiErrorBody, FieldViolation } from './types'

const NETWORK_ERROR_MESSAGE = 'Cannot reach the server. Check that the backend is running.'
const BACKEND_UNREACHABLE_MESSAGE = 'Cannot reach the backend API. Check that the Spring Boot server is running.'

export interface NormalizedError {
  status: number | null
  code: string
  message: string
  fieldErrors: FieldViolation[]
}

const FRIENDLY_MESSAGES: Record<string, string> = {
  AI_PROVIDER_NOT_CONFIGURED: 'The AI assistant is not configured yet. Ask an administrator to set OPENAI_API_KEY.',
  AI_PROVIDER_UNAVAILABLE: 'The AI service is temporarily unavailable. Please try again in a moment.',
  AI_PROVIDER_UNREACHABLE:
    'The AI service cannot be reached. If it runs on this computer (Ollama), start it and try again.',
  AI_PROVIDER_ERROR: 'The AI service rejected the request. Please contact an administrator.',
  AI_PROVIDER_QUOTA_EXCEEDED:
    'The AI service account has run out of credits or quota. An administrator needs to add credits.',
  AI_PROVIDER_EMPTY_RESPONSE: 'The AI service returned an empty answer. Please try again.',
  STREAM_ENDPOINT_MISSING:
    'The backend does not support streaming chat yet. Restart the Spring Boot server so it runs the latest code.',
  RATE_LIMITED: 'Too many requests. Please wait a moment and try again.',
  CONCURRENT_MODIFICATION: 'This record was changed by someone else. Reload it and apply your changes again.',
}

export function normalizeError(error: unknown): NormalizedError {
  if (error instanceof ChatStreamError) {
    if (error.code === 'BACKEND_UNREACHABLE') {
      return { status: error.status, code: error.code, message: BACKEND_UNREACHABLE_MESSAGE, fieldErrors: [] }
    }
    return {
      status: error.status,
      code: error.code,
      message:
        FRIENDLY_MESSAGES[error.code] ??
        (error.message || (error.status ? defaultMessage(error.status) : 'The request could not be completed.')),
      fieldErrors: [],
    }
  }
  if (error instanceof TypeError) {
    // fetch() rejects with a TypeError when the server cannot be reached.
    return { status: null, code: 'NETWORK_ERROR', message: NETWORK_ERROR_MESSAGE, fieldErrors: [] }
  }
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return {
        status: null,
        code: error.code === 'ECONNABORTED' ? 'TIMEOUT' : 'NETWORK_ERROR',
        message: error.code === 'ECONNABORTED' ? 'The request timed out. Please try again.' : NETWORK_ERROR_MESSAGE,
        fieldErrors: [],
      }
    }
    const data: unknown = error.response.data
    const body = (data && typeof data === 'object' ? data : {}) as ApiErrorBody
    if (!body.code && error.response.status >= 500) {
      // The backend always returns a coded ApiError; a bare 5xx comes from a proxy or gateway.
      return {
        status: error.response.status,
        code: 'BACKEND_UNREACHABLE',
        message: BACKEND_UNREACHABLE_MESSAGE,
        fieldErrors: [],
      }
    }
    const code = body.code ?? `HTTP_${error.response.status}`
    return {
      status: error.response.status,
      code,
      message: FRIENDLY_MESSAGES[code] ?? body.message ?? defaultMessage(error.response.status),
      fieldErrors: body.fieldErrors ?? [],
    }
  }
  return { status: null, code: 'UNKNOWN', message: 'An unexpected error occurred.', fieldErrors: [] }
}

function defaultMessage(status: number): string {
  if (status === 401) return 'Your session has expired. Please sign in again.'
  if (status === 403) return 'You do not have permission to perform this action.'
  if (status === 404) return 'The requested item was not found.'
  if (status >= 500) return 'The server encountered an error. Please try again later.'
  return 'The request could not be completed.'
}
