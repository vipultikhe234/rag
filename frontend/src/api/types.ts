export type Role = 'ADMIN' | 'USER'
export type ProductStatus = 'ACTIVE' | 'INACTIVE'
export type SyncState = 'SYNCED' | 'PENDING' | 'FAILED' | 'NOT_SYNCED'
export type VectorSyncStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
export type AttributeValue = string | number | boolean | Array<string | number | boolean>

export interface User {
  id: string
  email: string
  displayName: string
  role: Role
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresAt: string
  user: User
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface ProductSyncInfo {
  state: SyncState
  lastEventId: string | null
  lastEventType: string | null
  lastEventStatus: VectorSyncStatus | null
  lastError: string | null
  autoRetryScheduled: boolean
  nextAttemptAt: string | null
  embeddingModel: string | null
  embeddedAt: string | null
}

export interface Product {
  id: string
  name: string
  sku: string
  description: string
  category: string
  brand: string | null
  price: number
  currency: string
  stockQuantity: number
  attributes: Record<string, AttributeValue>
  status: ProductStatus
  version: number
  createdAt: string
  updatedAt: string
  vectorSync: ProductSyncInfo | null
}

export interface ProductPayload {
  name: string
  sku: string
  description: string
  category: string
  brand?: string | null
  price: number
  currency: string
  stockQuantity: number
  attributes: Record<string, AttributeValue>
  status?: ProductStatus
  version?: number
}

export interface ProductQuery {
  q?: string
  category?: string
  status?: ProductStatus | ''
  page: number
  size: number
  sort: string
}

export interface SourceReference {
  productId: string
  name: string
  sku: string
  category: string
  brand: string | null
  price: number
  currency: string
  stockQuantity: number
  status: ProductStatus
  relevanceScore: number | null
  matchType: 'EXACT_SKU' | 'NAME_MATCH' | 'STRUCTURED_FILTER' | 'SEMANTIC'
}

export interface RetrievalInfo {
  strategy: 'EXACT_SKU' | 'STRUCTURED' | 'HYBRID' | 'SEMANTIC' | 'CLARIFICATION' | string
  appliedFilters: string[]
  totalMatches: number | null
  llmUsed: boolean
}

export interface ChatAnswer {
  conversationId: string
  messageId: string
  answer: string
  sources: SourceReference[]
  retrieval: RetrievalInfo | null
  createdAt: string
}

export interface ConversationSummary {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface ChatMessage {
  id: string
  role: 'USER' | 'ASSISTANT'
  content: string
  sources: SourceReference[]
  retrieval: RetrievalInfo | null
  createdAt: string
}

export interface VectorSyncEvent {
  id: string
  productId: string
  productName: string | null
  productSku: string | null
  eventType: string
  status: VectorSyncStatus
  attempts: number
  maxAttempts: number
  retryable: boolean
  nextAttemptAt: string | null
  lastError: string | null
  result: string | null
  createdAt: string
  updatedAt: string
  processedAt: string | null
}

export interface VectorSyncStatusSummary {
  aiProviderConfigured: boolean
  workerEnabled: boolean
  embeddingModel: string
  embeddingDimensions: number
  pending: number
  processing: number
  completed: number
  failed: number
  failedRetryable: number
  failedPermanent: number
  oldestPendingAt: string | null
  embeddedProducts: number
  activeProductsWithoutEmbedding: number
  recentFailures: VectorSyncEvent[]
}

export interface FieldViolation {
  field: string
  message: string
}

export interface ApiErrorBody {
  timestamp?: string
  status?: number
  error?: string
  code?: string
  message?: string
  path?: string
  fieldErrors?: FieldViolation[]
}
