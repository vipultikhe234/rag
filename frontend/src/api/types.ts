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
  categoryId: string | null
  brand: string | null
  brandId: string | null
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
  categoryId: string
  brandId?: string | null
  price: number
  currency: string
  stockQuantity: number
  attributes: Record<string, AttributeValue>
  status?: ProductStatus
  version?: number
}

export interface ProductQuery {
  q?: string
  categoryId?: string
  brandId?: string
  /** "slug:value" pairs for filterable attributes */
  attr?: string[]
  status?: ProductStatus | ''
  page: number
  size: number
  sort: string
}

export type CatalogStatus = 'ACTIVE' | 'INACTIVE'

export interface Category {
  id: string
  name: string
  slug: string
  description: string | null
  imageUrl: string | null
  parentId: string | null
  parentName: string | null
  status: CatalogStatus
  productCount: number
  subcategoryCount: number
  attributeCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface CategoryOption {
  id: string
  name: string
  slug: string
  parentId: string | null
  /** Full hierarchy, e.g. "Electronics / Laptops" */
  path: string
  status: CatalogStatus
}

export interface CategoryPayload {
  name: string
  slug?: string | null
  description?: string | null
  imageUrl?: string | null
  parentId?: string | null
  status?: CatalogStatus
  version?: number
}

export interface Brand {
  id: string
  name: string
  slug: string
  description: string | null
  logoUrl: string | null
  websiteUrl: string | null
  status: CatalogStatus
  productCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface BrandOption {
  id: string
  name: string
  slug: string
  status: CatalogStatus
}

export interface BrandPayload {
  name: string
  slug?: string | null
  description?: string | null
  logoUrl?: string | null
  websiteUrl?: string | null
  status?: CatalogStatus
  version?: number
}

export type AttributeDataType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'SELECT'

export interface ProductAttribute {
  id: string
  categoryId: string
  categoryName: string | null
  name: string
  slug: string
  dataType: AttributeDataType
  options: string[]
  unit: string | null
  required: boolean
  filterable: boolean
  status: CatalogStatus
  version: number
  createdAt: string
  updatedAt: string
}

export interface ProductAttributePayload {
  categoryId: string
  name: string
  slug?: string | null
  dataType: AttributeDataType
  options: string[]
  unit?: string | null
  required: boolean
  filterable: boolean
  status?: CatalogStatus
  version?: number
}

export type StockLevel = 'IN_STOCK' | 'LOW_STOCK' | 'OUT_OF_STOCK'
export type StockFilter = 'AVAILABLE' | StockLevel

export interface ProductVariant {
  id: string
  productId: string
  productName: string
  productSku: string
  productStatus: ProductStatus
  currency: string
  sku: string
  variantName: string
  attributes: Record<string, AttributeValue>
  price: number
  salePrice: number | null
  effectivePrice: number
  stockQuantity: number
  stockLevel: StockLevel
  status: CatalogStatus
  version: number
  createdAt: string
  updatedAt: string
}

export interface ProductVariantPayload {
  productId: string
  sku: string
  variantName: string
  attributes: Record<string, AttributeValue>
  price: number
  salePrice: number | null
  stockQuantity: number
  status?: CatalogStatus
  version?: number
}

export interface ListQuery {
  q?: string
  status?: CatalogStatus | ''
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
