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

export interface ProductImage {
  id: string
  url: string
  altText: string | null
  position: number
}

export interface ProductImagePayload {
  /** Present for images that already exist; new images only send the URL. */
  id?: string
  url: string
  altText?: string | null
}

export interface ProductOptionValue {
  id: string
  value: string
  position: number
}

export interface ProductOption {
  id: string
  name: string
  attributeId: string | null
  /** Unit of a linked NUMBER attribute (e.g. "GB"), appended to values for display. */
  unit: string | null
  position: number
  values: ProductOptionValue[]
}

export interface ProductOptionPayload {
  id?: string
  name: string
  attributeId?: string | null
  values: Array<{ id?: string; value: string }>
}

export interface VariantSummary {
  count: number
  activeCount: number
  minPrice: number | null
  maxPrice: number | null
}

export interface Product {
  id: string
  name: string
  sku: string
  slug: string
  description: string
  category: string
  categoryId: string | null
  brand: string | null
  brandId: string | null
  /** For variant products: the lowest active variant price (read-only). */
  price: number
  currency: string
  /** For variant products: the total stock of active variants (read-only). */
  stockQuantity: number
  attributes: Record<string, AttributeValue>
  status: ProductStatus
  version: number
  createdAt: string
  updatedAt: string
  vectorSync: ProductSyncInfo | null
  hasVariants: boolean
  images: ProductImage[]
  options: ProductOption[]
  variantSummary: VariantSummary
}

/** A new variant sent together with its product (or in a bulk request for one product). */
export interface VariantDraft {
  sku: string
  /** Option name -> value, e.g. { Color: 'Black', Storage: '256' } */
  options: Record<string, string>
  attributes?: Record<string, AttributeValue>
  price: number
  compareAtPrice?: number | null
  stockQuantity: number
  barcode?: string | null
  imageId?: string | null
  /** URL of one of the product's images; used when the image has no id yet. */
  imageUrl?: string | null
  status?: CatalogStatus
}

export interface ProductPayload {
  name: string
  sku: string
  slug?: string | null
  description: string
  categoryId: string
  brandId?: string | null
  /** Required for simple products; ignored for variant products. */
  price?: number | null
  currency: string
  stockQuantity?: number | null
  attributes: Record<string, AttributeValue>
  status?: ProductStatus
  version?: number
  hasVariants?: boolean
  images?: ProductImagePayload[]
  options?: ProductOptionPayload[]
  /** New variants only; existing variants are edited through the variant endpoints. */
  variants?: VariantDraft[]
}

export interface ProductQuery {
  q?: string
  categoryId?: string
  brandId?: string
  /** "slug:value" pairs for filterable attributes */
  attr?: string[]
  status?: ProductStatus | ''
  minPrice?: string
  maxPrice?: string
  stock?: StockFilter | ''
  hasVariants?: boolean
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

export interface AttributeCategoryRef {
  id: string
  name: string
  /** The category's own status. */
  active: boolean
}

export interface ProductAttribute {
  id: string
  /** Categories the attribute is assigned to; it also applies to their subcategories. */
  categories: AttributeCategoryRef[]
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
  categoryIds: string[]
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

/** The value a variant has for one option; `label` includes the unit, e.g. "256 GB". */
export interface SelectedOption {
  optionId: string
  name: string
  valueId: string
  value: string
  label: string
}

export interface ProductVariant {
  id: string
  productId: string
  productName: string
  productSku: string
  productStatus: ProductStatus
  categoryId: string | null
  category: string | null
  brandId: string | null
  brand: string | null
  currency: string
  sku: string
  /** Generated title, e.g. "Black / 256 GB". */
  variantName: string
  options: SelectedOption[]
  attributes: Record<string, AttributeValue>
  price: number
  compareAtPrice: number | null
  onSale: boolean
  stockQuantity: number
  stockLevel: StockLevel
  barcode: string | null
  imageId: string | null
  imageUrl: string | null
  status: CatalogStatus
  version: number
  createdAt: string
  updatedAt: string
}

export interface ProductVariantPayload {
  productId: string
  sku: string
  options: Record<string, string>
  attributes: Record<string, AttributeValue>
  price: number
  compareAtPrice: number | null
  stockQuantity: number
  barcode?: string | null
  imageId?: string | null
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
  strategy: 'EXACT_SKU' | 'STRUCTURED' | 'HYBRID' | 'SEMANTIC' | 'CLARIFICATION' | 'CATALOG' | 'DISCOVERY' | string
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

export type CatalogEntityType = 'BRAND' | 'CATEGORY' | 'ATTRIBUTE' | 'VARIANT'

export interface VectorSyncEvent {
  id: string
  /** PRODUCT or a CatalogEntityType; productId/productName/productSku then describe that entity. */
  aggregateType: 'PRODUCT' | CatalogEntityType
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
  catalogIndex: CatalogIndexStatus[]
  recentFailures: VectorSyncEvent[]
}

export interface CatalogIndexStatus {
  entityType: CatalogEntityType
  embedded: number
  activeWithoutEmbedding: number
}

export interface StaleScanCounts {
  checked: number
  stale: number
  enqueued: number
}

/** Top-level counts are products; `catalog` covers brands, categories, attributes and variants. */
export interface RefreshStaleResult extends StaleScanCounts {
  catalog: StaleScanCounts & { byType: Partial<Record<CatalogEntityType, StaleScanCounts>> }
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
