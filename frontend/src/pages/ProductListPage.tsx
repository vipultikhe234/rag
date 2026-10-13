import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router'
import { productApi, vectorSyncApi } from '../api/endpoints'
import { normalizeError } from '../api/errors'
import type { PageResponse, Product, ProductStatus } from '../api/types'
import { useAuth } from '../auth/authContext'
import { useToast } from '../components/toast/toastContext'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { SelectField, TextField } from '../components/ui/FormField'
import { Pagination } from '../components/ui/Pagination'
import { LoadingBlock } from '../components/ui/Spinner'
import { EmptyState, ErrorState, InlineAlert } from '../components/ui/States'
import { StatusBadge, SyncBadge } from '../features/products/badges'
import { VectorSyncPanel } from '../features/products/VectorSyncPanel'
import { formatPrice } from '../lib/format'

const PAGE_SIZE = 20
const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'updatedAt,desc', label: 'Recently updated' },
  { value: 'name,asc', label: 'Name (A–Z)' },
  { value: 'price,asc', label: 'Price (low to high)' },
  { value: 'price,desc', label: 'Price (high to low)' },
  { value: 'stockQuantity,asc', label: 'Stock (low to high)' },
]

export function ProductListPage() {
  const { isAdmin } = useAuth()
  const toast = useToast()
  const location = useLocation()
  const [params, setParams] = useSearchParams()

  const q = params.get('q') ?? ''
  const category = params.get('category') ?? ''
  const status = (params.get('status') ?? '') as ProductStatus | ''
  const sort = params.get('sort') ?? 'createdAt,desc'
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)

  const [searchInput, setSearchInput] = useState(q)
  const [data, setData] = useState<PageResponse<Product> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [categories, setCategories] = useState<string[]>([])
  const [reloadKey, setReloadKey] = useState(0)
  const [pendingDelete, setPendingDelete] = useState<Product | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const forbidden = (location.state as { forbidden?: boolean } | null)?.forbidden === true
  const reload = useCallback(() => setReloadKey((key) => key + 1), [])

  const updateParams = useCallback(
    (changes: Record<string, string | null>) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current)
          for (const [key, value] of Object.entries(changes)) {
            if (value === null || value === '') next.delete(key)
            else next.set(key, value)
          }
          if (!('page' in changes)) next.delete('page')
          return next
        },
        { replace: true },
      )
    },
    [setParams],
  )

  // Debounce the search box into the URL.
  useEffect(() => {
    if (searchInput === q) return
    const timer = window.setTimeout(() => updateParams({ q: searchInput.trim() || null }), 300)
    return () => window.clearTimeout(timer)
  }, [searchInput, q, updateParams])

  useEffect(() => {
    const controller = new AbortController()
    productApi
      .list({ q, category, status, page, size: PAGE_SIZE, sort }, controller.signal)
      .then((result) => {
        setData(result)
        setError(null)
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted) setError(normalizeError(err).message)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [q, category, status, page, sort, reloadKey])

  useEffect(() => {
    productApi
      .categories()
      .then(setCategories)
      .catch(() => setCategories([]))
  }, [reloadKey])

  // Vector sync is asynchronous: refresh while any visible product is still being indexed.
  const hasPendingSync = data?.content.some((product) => product.vectorSync?.state === 'PENDING') ?? false
  useEffect(() => {
    if (!hasPendingSync) return
    const timer = window.setTimeout(reload, 4000)
    return () => window.clearTimeout(timer)
  }, [hasPendingSync, data, reload])

  const toggleStatus = async (product: Product) => {
    const next: ProductStatus = product.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
    setBusyId(product.id)
    try {
      await productApi.setStatus(product.id, next)
      toast.success(`${product.name} ${next === 'ACTIVE' ? 'activated' : 'deactivated'}.`)
      reload()
    } catch (err) {
      toast.error(normalizeError(err).message)
    } finally {
      setBusyId(null)
    }
  }

  const confirmDelete = async () => {
    if (!pendingDelete) return
    setBusyId(pendingDelete.id)
    try {
      await productApi.remove(pendingDelete.id)
      toast.success(`${pendingDelete.name} deleted.`)
      setPendingDelete(null)
      if (data && data.content.length === 1 && page > 0) {
        updateParams({ page: String(page - 1) })
      } else {
        reload()
      }
    } catch (err) {
      toast.error(normalizeError(err).message)
    } finally {
      setBusyId(null)
    }
  }

  const retrySync = async (product: Product) => {
    if (!product.vectorSync?.lastEventId) return
    setBusyId(product.id)
    try {
      await vectorSyncApi.retry(product.vectorSync.lastEventId)
      toast.success(`Indexing of ${product.name} re-queued.`)
      reload()
    } catch (err) {
      toast.error(normalizeError(err).message)
    } finally {
      setBusyId(null)
    }
  }

  const filtersActive = Boolean(q || category || status)

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Product Management</h1>
          <p className="text-sm text-slate-500">Manage the catalog that powers AI Chat answers.</p>
        </div>
        {isAdmin && (
          <Link
            to="/products/new"
            className="inline-flex h-10 items-center rounded-md bg-indigo-600 px-4 text-sm font-medium text-white hover:bg-indigo-500 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2"
          >
            Add product
          </Link>
        )}
      </div>

      {forbidden && <InlineAlert tone="amber">Only administrators can create or edit products.</InlineAlert>}

      {isAdmin && <VectorSyncPanel refreshKey={reloadKey} onChanged={reload} />}

      <Card>
        <form
          className="grid gap-4 md:grid-cols-[2fr_1fr_1fr_1fr]"
          role="search"
          aria-label="Filter products"
          onSubmit={(event) => {
            event.preventDefault()
            updateParams({ q: searchInput.trim() || null })
          }}
        >
          <TextField
            label="Search"
            type="search"
            placeholder="Name, SKU, category or brand"
            value={searchInput}
            maxLength={100}
            onChange={(event) => setSearchInput(event.target.value)}
          />
          <SelectField label="Category" value={category} onChange={(event) => updateParams({ category: event.target.value })}>
            <option value="">All categories</option>
            {categories.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </SelectField>
          <SelectField label="Status" value={status} onChange={(event) => updateParams({ status: event.target.value })}>
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </SelectField>
          <SelectField label="Sort by" value={sort} onChange={(event) => updateParams({ sort: event.target.value })}>
            {SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
        </form>

        <div className="mt-6">
          {loading && !data ? (
            <LoadingBlock label="Loading products" />
          ) : error && !data ? (
            <ErrorState message={error} onRetry={reload} />
          ) : data && data.content.length === 0 ? (
            <EmptyState
              title={filtersActive ? 'No products match your filters' : 'No products yet'}
              description={
                filtersActive
                  ? 'Try a different search term or clear the filters.'
                  : isAdmin
                    ? 'Add your first product to make it available to AI Chat.'
                    : 'An administrator has not added any products yet.'
              }
              action={
                filtersActive ? (
                  <Button
                    variant="secondary"
                    onClick={() => {
                      setSearchInput('')
                      updateParams({ q: null, category: null, status: null })
                    }}
                  >
                    Clear filters
                  </Button>
                ) : undefined
              }
            />
          ) : data ? (
            <>
              {error && (
                <div className="mb-3">
                  <InlineAlert tone="red">{error}</InlineAlert>
                </div>
              )}
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-slate-200 text-sm">
                  <caption className="sr-only">Products</caption>
                  <thead className="bg-slate-50 text-left text-xs font-semibold uppercase tracking-wide text-slate-500">
                    <tr>
                      <th scope="col" className="px-3 py-3">Product</th>
                      <th scope="col" className="px-3 py-3">Category</th>
                      <th scope="col" className="px-3 py-3 text-right">Price</th>
                      <th scope="col" className="px-3 py-3 text-right">Stock</th>
                      <th scope="col" className="px-3 py-3">Status</th>
                      <th scope="col" className="px-3 py-3">AI index</th>
                      {isAdmin && (
                        <th scope="col" className="px-3 py-3 text-right">
                          <span className="sr-only">Actions</span>
                        </th>
                      )}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100 bg-white">
                    {data.content.map((product) => (
                      <tr key={product.id} className="align-top hover:bg-slate-50">
                        <td className="px-3 py-3">
                          <div className="font-medium text-slate-900">{product.name}</div>
                          <div className="font-mono text-xs text-slate-500">{product.sku}</div>
                        </td>
                        <td className="px-3 py-3">
                          <div>{product.category}</div>
                          {product.brand && <div className="text-xs text-slate-500">{product.brand}</div>}
                        </td>
                        <td className="whitespace-nowrap px-3 py-3 text-right tabular-nums">
                          {formatPrice(product.price, product.currency)}
                        </td>
                        <td className="px-3 py-3 text-right tabular-nums">
                          <span className={product.stockQuantity === 0 ? 'font-medium text-red-600' : undefined}>
                            {product.stockQuantity}
                          </span>
                        </td>
                        <td className="px-3 py-3">
                          <StatusBadge status={product.status} />
                        </td>
                        <td className="px-3 py-3">
                          <div className="flex flex-col items-start gap-1">
                            <SyncBadge sync={product.vectorSync} />
                            {isAdmin && product.vectorSync?.state === 'FAILED' && product.vectorSync.lastEventId && (
                              <button
                                type="button"
                                className="text-xs font-medium text-indigo-600 hover:text-indigo-500 focus:outline-none focus-visible:underline"
                                onClick={() => void retrySync(product)}
                                disabled={busyId === product.id}
                              >
                                Retry now
                              </button>
                            )}
                          </div>
                        </td>
                        {isAdmin && (
                          <td className="whitespace-nowrap px-3 py-3 text-right">
                            <div className="flex justify-end gap-1">
                              <Link
                                to={`/products/${product.id}/edit`}
                                className="inline-flex h-8 items-center rounded-md px-3 text-xs font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                                aria-label={`Edit ${product.name}`}
                              >
                                Edit
                              </Link>
                              <Button
                                variant="ghost"
                                size="sm"
                                loading={busyId === product.id && !pendingDelete}
                                onClick={() => void toggleStatus(product)}
                                aria-label={`${product.status === 'ACTIVE' ? 'Deactivate' : 'Activate'} ${product.name}`}
                              >
                                {product.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                className="text-red-600 hover:bg-red-50 hover:text-red-700"
                                onClick={() => setPendingDelete(product)}
                                aria-label={`Delete ${product.name}`}
                              >
                                Delete
                              </Button>
                            </div>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination
                page={data.page}
                size={data.size}
                totalPages={data.totalPages}
                totalElements={data.totalElements}
                onPageChange={(next) => updateParams({ page: String(next) })}
              />
            </>
          ) : null}
        </div>
      </Card>

      <ConfirmDialog
        open={pendingDelete !== null}
        title="Delete product?"
        confirmLabel="Delete product"
        busy={pendingDelete !== null && busyId === pendingDelete.id}
        onConfirm={() => void confirmDelete()}
        onCancel={() => setPendingDelete(null)}
      >
        <p>
          <strong>{pendingDelete?.name}</strong> ({pendingDelete?.sku}) will be permanently deleted and removed from AI
          search. This cannot be undone. To hide a product temporarily, deactivate it instead.
        </p>
      </ConfirmDialog>
    </div>
  )
}
