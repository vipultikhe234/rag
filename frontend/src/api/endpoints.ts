import { api } from './client'
import type {
  ChatAnswer,
  ChatMessage,
  ConversationSummary,
  LoginResponse,
  PageResponse,
  Product,
  ProductPayload,
  ProductQuery,
  ProductStatus,
  User,
  VectorSyncEvent,
  VectorSyncStatus,
  VectorSyncStatusSummary,
} from './types'

export const authApi = {
  login: (email: string, password: string) =>
    api.post<LoginResponse>('/auth/login', { email, password }).then((r) => r.data),
  me: () => api.get<User>('/auth/me').then((r) => r.data),
}

export const productApi = {
  list: (query: ProductQuery, signal?: AbortSignal) =>
    api
      .get<PageResponse<Product>>('/products', {
        params: {
          q: query.q || undefined,
          category: query.category || undefined,
          status: query.status || undefined,
          page: query.page,
          size: query.size,
          sort: query.sort,
        },
        signal,
      })
      .then((r) => r.data),
  categories: () => api.get<string[]>('/products/categories').then((r) => r.data),
  get: (id: string, signal?: AbortSignal) => api.get<Product>(`/products/${id}`, { signal }).then((r) => r.data),
  create: (payload: ProductPayload) => api.post<Product>('/products', payload).then((r) => r.data),
  update: (id: string, payload: ProductPayload) => api.put<Product>(`/products/${id}`, payload).then((r) => r.data),
  setStatus: (id: string, status: ProductStatus) =>
    api.patch<Product>(`/products/${id}/status`, { status }).then((r) => r.data),
  remove: (id: string) => api.delete<void>(`/products/${id}`).then(() => undefined),
}

export const chatApi = {
  ask: (question: string, conversationId: string | null) =>
    api.post<ChatAnswer>('/chat', { question, conversationId }).then((r) => r.data),
  conversations: (page = 0, size = 50) =>
    api
      .get<PageResponse<ConversationSummary>>('/chat/conversations', { params: { page, size } })
      .then((r) => r.data),
  messages: (conversationId: string, signal?: AbortSignal) =>
    api.get<ChatMessage[]>(`/chat/conversations/${conversationId}/messages`, { signal }).then((r) => r.data),
  renameConversation: (conversationId: string, title: string) =>
    api.patch<ConversationSummary>(`/chat/conversations/${conversationId}`, { title }).then((r) => r.data),
  deleteConversation: (conversationId: string) =>
    api.delete<void>(`/chat/conversations/${conversationId}`).then(() => undefined),
}

export const vectorSyncApi = {
  status: () => api.get<VectorSyncStatusSummary>('/admin/vector-sync/status').then((r) => r.data),
  events: (params: { status?: VectorSyncStatus; productId?: string; page?: number; size?: number }) =>
    api.get<PageResponse<VectorSyncEvent>>('/admin/vector-sync/events', { params }).then((r) => r.data),
  retry: (eventId: string) => api.post<VectorSyncEvent>(`/admin/vector-sync/retry/${eventId}`).then((r) => r.data),
  retryAllFailed: () => api.post<{ requeued: number }>('/admin/vector-sync/retry-failed').then((r) => r.data),
  resyncMissing: () => api.post<{ enqueued: number }>('/admin/vector-sync/resync-missing').then((r) => r.data),
}
