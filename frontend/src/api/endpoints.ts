import { api } from './client'
import type {
  AttributeDataType,
  Brand,
  BrandOption,
  BrandPayload,
  CatalogStatus,
  Category,
  CategoryOption,
  CategoryPayload,
  ChatAnswer,
  ChatMessage,
  ConversationSummary,
  ListQuery,
  LoginResponse,
  PageResponse,
  Product,
  ProductAttribute,
  ProductAttributePayload,
  ProductPayload,
  ProductQuery,
  ProductStatus,
  ProductVariant,
  ProductVariantPayload,
  StockFilter,
  User,
  VariantDraft,
  VectorSyncEvent,
  VectorSyncStatus,
  VectorSyncStatusSummary,
} from './types'

// Spring binds repeated parameters (attr=a&attr=b), not axios' default bracket style (attr[]=a).
const repeatParams = { indexes: null }

function listParams(query: ListQuery) {
  return {
    q: query.q || undefined,
    status: query.status || undefined,
    page: query.page,
    size: query.size,
    sort: query.sort,
  }
}

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
          categoryId: query.categoryId || undefined,
          brandId: query.brandId || undefined,
          attr: query.attr && query.attr.length > 0 ? query.attr : undefined,
          status: query.status || undefined,
          minPrice: query.minPrice || undefined,
          maxPrice: query.maxPrice || undefined,
          stock: query.stock || undefined,
          hasVariants: query.hasVariants,
          page: query.page,
          size: query.size,
          sort: query.sort,
        },
        paramsSerializer: repeatParams,
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
  variants: (id: string, signal?: AbortSignal) =>
    api.get<ProductVariant[]>(`/products/${id}/variants`, { signal }).then((r) => r.data),
  addVariants: (id: string, variants: VariantDraft[]) =>
    api.post<ProductVariant[]>(`/products/${id}/variants`, { variants }).then((r) => r.data),
}

export interface CategoryQuery extends ListQuery {
  parentId?: string
  rootsOnly?: boolean
}

export const categoryApi = {
  list: (query: CategoryQuery, signal?: AbortSignal) =>
    api
      .get<PageResponse<Category>>('/categories', {
        params: { ...listParams(query), parentId: query.parentId || undefined, rootsOnly: query.rootsOnly || undefined },
        signal,
      })
      .then((r) => r.data),
  options: (includeInactive: boolean, signal?: AbortSignal) =>
    api.get<CategoryOption[]>('/categories/options', { params: { includeInactive }, signal }).then((r) => r.data),
  get: (id: string, signal?: AbortSignal) => api.get<Category>(`/categories/${id}`, { signal }).then((r) => r.data),
  create: (payload: CategoryPayload) => api.post<Category>('/categories', payload).then((r) => r.data),
  update: (id: string, payload: CategoryPayload) => api.put<Category>(`/categories/${id}`, payload).then((r) => r.data),
  setStatus: (id: string, status: CatalogStatus) =>
    api.patch<Category>(`/categories/${id}/status`, { status }).then((r) => r.data),
  remove: (id: string) => api.delete<void>(`/categories/${id}`).then(() => undefined),
}

export const brandApi = {
  list: (query: ListQuery, signal?: AbortSignal) =>
    api.get<PageResponse<Brand>>('/brands', { params: listParams(query), signal }).then((r) => r.data),
  options: (includeInactive: boolean, signal?: AbortSignal) =>
    api.get<BrandOption[]>('/brands/options', { params: { includeInactive }, signal }).then((r) => r.data),
  get: (id: string, signal?: AbortSignal) => api.get<Brand>(`/brands/${id}`, { signal }).then((r) => r.data),
  create: (payload: BrandPayload) => api.post<Brand>('/brands', payload).then((r) => r.data),
  update: (id: string, payload: BrandPayload) => api.put<Brand>(`/brands/${id}`, payload).then((r) => r.data),
  setStatus: (id: string, status: CatalogStatus) =>
    api.patch<Brand>(`/brands/${id}/status`, { status }).then((r) => r.data),
  remove: (id: string) => api.delete<void>(`/brands/${id}`).then(() => undefined),
}

export interface AttributeQuery extends ListQuery {
  categoryId?: string
  dataType?: AttributeDataType | ''
  filterable?: boolean
  required?: boolean
}

export const attributeApi = {
  list: (query: AttributeQuery, signal?: AbortSignal) =>
    api
      .get<PageResponse<ProductAttribute>>('/product-attributes', {
        params: {
          ...listParams(query),
          categoryId: query.categoryId || undefined,
          dataType: query.dataType || undefined,
          filterable: query.filterable,
          required: query.required,
        },
        signal,
      })
      .then((r) => r.data),
  /** Attributes that apply to products in the category, including those inherited from parent categories. */
  applicable: (categoryId: string, includeInactive: boolean, signal?: AbortSignal) =>
    api
      .get<ProductAttribute[]>('/product-attributes/applicable', { params: { categoryId, includeInactive }, signal })
      .then((r) => r.data),
  get: (id: string, signal?: AbortSignal) =>
    api.get<ProductAttribute>(`/product-attributes/${id}`, { signal }).then((r) => r.data),
  create: (payload: ProductAttributePayload) =>
    api.post<ProductAttribute>('/product-attributes', payload).then((r) => r.data),
  update: (id: string, payload: ProductAttributePayload) =>
    api.put<ProductAttribute>(`/product-attributes/${id}`, payload).then((r) => r.data),
  setStatus: (id: string, status: CatalogStatus) =>
    api.patch<ProductAttribute>(`/product-attributes/${id}/status`, { status }).then((r) => r.data),
}

export interface VariantQuery extends ListQuery {
  productId?: string
  categoryId?: string
  brandId?: string
  stock?: StockFilter | ''
  minPrice?: string
  maxPrice?: string
  /** "Option name:value" pairs, e.g. "Color:Black" (at most 5) */
  option?: string[]
}

export const variantApi = {
  list: (query: VariantQuery, signal?: AbortSignal) =>
    api
      .get<PageResponse<ProductVariant>>('/product-variants', {
        params: {
          ...listParams(query),
          productId: query.productId || undefined,
          categoryId: query.categoryId || undefined,
          brandId: query.brandId || undefined,
          stock: query.stock || undefined,
          minPrice: query.minPrice || undefined,
          maxPrice: query.maxPrice || undefined,
          option: query.option && query.option.length > 0 ? query.option : undefined,
        },
        paramsSerializer: repeatParams,
        signal,
      })
      .then((r) => r.data),
  get: (id: string, signal?: AbortSignal) =>
    api.get<ProductVariant>(`/product-variants/${id}`, { signal }).then((r) => r.data),
  create: (payload: ProductVariantPayload) =>
    api.post<ProductVariant>('/product-variants', payload).then((r) => r.data),
  update: (id: string, payload: ProductVariantPayload) =>
    api.put<ProductVariant>(`/product-variants/${id}`, payload).then((r) => r.data),
  setStatus: (id: string, status: CatalogStatus) =>
    api.patch<ProductVariant>(`/product-variants/${id}/status`, { status }).then((r) => r.data),
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
