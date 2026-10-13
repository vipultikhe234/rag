import { useState } from 'react'
import { Link } from 'react-router'
import { productApi, variantApi } from '../../api/endpoints'
import type { CatalogStatus, StockFilter } from '../../api/types'
import { useAuth } from '../../auth/authContext'
import { Card } from '../../components/ui/Card'
import { SelectField, TextField } from '../../components/ui/FormField'
import { Pagination } from '../../components/ui/Pagination'
import { ListBody, PageHeader, StatusToggle, StockBadge } from '../../features/catalog/CatalogUi'
import { useDebouncedSearch, useListParams, useLoader } from '../../features/catalog/hooks'
import { deactivateHints } from '../../features/catalog/messages'
import { linkClass, table } from '../../features/catalog/styles'
import { StatusBadge } from '../../features/products/badges'
import { formatPrice } from '../../lib/format'

const PAGE_SIZE = 20
const PRICE = /^\d{0,10}(\.\d{0,2})?$/
const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'variantName,asc', label: 'Name (A–Z)' },
  { value: 'productName,asc', label: 'Product (A–Z)' },
  { value: 'price,asc', label: 'Price (low to high)' },
  { value: 'price,desc', label: 'Price (high to low)' },
  { value: 'stockQuantity,asc', label: 'Stock (low to high)' },
  { value: 'updatedAt,desc', label: 'Recently updated' },
]
const STOCK_OPTIONS: Array<{ value: StockFilter; label: string }> = [
  { value: 'AVAILABLE', label: 'Available (any stock)' },
  { value: 'IN_STOCK', label: 'In stock' },
  { value: 'LOW_STOCK', label: 'Low stock' },
  { value: 'OUT_OF_STOCK', label: 'Out of stock' },
]

export function VariantListPage() {
  const { isAdmin } = useAuth()
  const { params, updateParams, page } = useListParams()
  const q = params.get('q') ?? ''
  const status = (params.get('status') ?? '') as CatalogStatus | ''
  const productId = params.get('productId') ?? ''
  const stock = (params.get('stock') ?? '') as StockFilter | ''
  const minPrice = params.get('minPrice') ?? ''
  const maxPrice = params.get('maxPrice') ?? ''
  const sort = params.get('sort') ?? 'createdAt,desc'
  const [searchInput, setSearchInput] = useDebouncedSearch(q, updateParams)
  const [minInput, setMinInput] = useState(minPrice)
  const [maxInput, setMaxInput] = useState(maxPrice)

  const products = useLoader(
    (signal) => productApi.list({ page: 0, size: 100, sort: 'name,asc' }, signal),
    'products',
  )
  const list = useLoader(
    (signal) =>
      variantApi.list({ q, status, productId, stock, minPrice, maxPrice, page, size: PAGE_SIZE, sort }, signal),
    JSON.stringify([q, status, productId, stock, minPrice, maxPrice, sort, page]),
  )
  const filtersActive = Boolean(q || status || productId || stock || minPrice || maxPrice)
  const selectedProduct = products.data?.content.find((p) => p.id === productId)
  const priceInvalid = !PRICE.test(minInput.trim()) || !PRICE.test(maxInput.trim())

  const applyPrices = () => {
    if (priceInvalid) return
    updateParams({ minPrice: minInput.trim() || null, maxPrice: maxInput.trim() || null })
  }

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader
        title={selectedProduct ? `Variants of ${selectedProduct.name}` : 'Product variants'}
        description="Sellable versions of a product, such as a colour or storage option, each with its own SKU, price and stock."
        actions={
          isAdmin && (
            <Link
              to={productId ? `/product-variants/new?productId=${productId}` : '/product-variants/new'}
              className={linkClass.primary}
            >
              Add variant
            </Link>
          )
        }
      />

      <Card>
        <form
          className="grid gap-4 md:grid-cols-3 xl:grid-cols-[2fr_1.5fr_1fr_0.8fr_0.8fr_1fr_1fr]"
          role="search"
          aria-label="Filter variants"
          onSubmit={(event) => {
            event.preventDefault()
            updateParams({ q: searchInput.trim() || null })
            applyPrices()
          }}
        >
          <TextField
            label="Search"
            type="search"
            placeholder="Variant name or SKU"
            value={searchInput}
            maxLength={100}
            onChange={(event) => setSearchInput(event.target.value)}
          />
          <SelectField label="Product" value={productId} onChange={(event) => updateParams({ productId: event.target.value })}>
            <option value="">All products</option>
            {(products.data?.content ?? []).map((product) => (
              <option key={product.id} value={product.id}>
                {product.name} ({product.sku})
              </option>
            ))}
          </SelectField>
          <SelectField label="Stock" value={stock} onChange={(event) => updateParams({ stock: event.target.value })}>
            <option value="">Any stock</option>
            {STOCK_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Min price"
            inputMode="decimal"
            value={minInput}
            error={!PRICE.test(minInput.trim()) ? 'Invalid amount' : undefined}
            onChange={(event) => setMinInput(event.target.value)}
            onBlur={applyPrices}
          />
          <TextField
            label="Max price"
            inputMode="decimal"
            value={maxInput}
            error={!PRICE.test(maxInput.trim()) ? 'Invalid amount' : undefined}
            onChange={(event) => setMaxInput(event.target.value)}
            onBlur={applyPrices}
          />
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
          <ListBody
            loading={list.loading}
            error={list.error}
            data={list.data}
            onRetry={list.reload}
            label="variants"
            filtersActive={filtersActive}
            onClearFilters={() => {
              setSearchInput('')
              setMinInput('')
              setMaxInput('')
              updateParams({ q: null, status: null, productId: null, stock: null, minPrice: null, maxPrice: null })
            }}
            emptyDescription={isAdmin ? 'Add a variant to sell a product in several versions.' : 'No variants have been created yet.'}
          >
            {(items) => (
              <div className="overflow-x-auto">
                <table className={table.table}>
                  <caption className="sr-only">Product variants</caption>
                  <thead className={table.head}>
                    <tr>
                      <th scope="col" className={table.th}>Variant</th>
                      <th scope="col" className={table.th}>Product</th>
                      <th scope="col" className={`${table.th} text-right`}>Price</th>
                      <th scope="col" className={`${table.th} text-right`}>Stock</th>
                      <th scope="col" className={table.th}>Status</th>
                      {isAdmin && (
                        <th scope="col" className={`${table.th} text-right`}>
                          <span className="sr-only">Actions</span>
                        </th>
                      )}
                    </tr>
                  </thead>
                  <tbody className={table.body}>
                    {items.map((variant) => (
                      <tr key={variant.id} className={table.row}>
                        <td className={table.td}>
                          <Link to={`/product-variants/${variant.id}`} className={linkClass.text}>
                            {variant.variantName}
                          </Link>
                          <div className="font-mono text-xs text-slate-500">{variant.sku}</div>
                        </td>
                        <td className={table.td}>
                          <button
                            type="button"
                            className="text-left text-slate-700 hover:underline"
                            onClick={() => updateParams({ productId: variant.productId })}
                            title="Show only this product's variants"
                          >
                            {variant.productName}
                          </button>
                          <div className="font-mono text-xs text-slate-500">{variant.productSku}</div>
                        </td>
                        <td className={`${table.td} whitespace-nowrap text-right tabular-nums`}>
                          {formatPrice(variant.effectivePrice, variant.currency)}
                          {variant.salePrice !== null && (
                            <div className="text-xs text-slate-400 line-through">
                              {formatPrice(variant.price, variant.currency)}
                            </div>
                          )}
                        </td>
                        <td className={`${table.td} whitespace-nowrap text-right`}>
                          <span className="tabular-nums">{variant.stockQuantity}</span>
                          <div className="mt-1">
                            <StockBadge level={variant.stockLevel} />
                          </div>
                        </td>
                        <td className={table.td}>
                          <StatusBadge status={variant.status} />
                        </td>
                        {isAdmin && (
                          <td className={`${table.td} whitespace-nowrap text-right`}>
                            <div className="flex justify-end gap-1">
                              <Link
                                to={`/product-variants/${variant.id}/edit`}
                                className={linkClass.row}
                                aria-label={`Edit ${variant.variantName}`}
                              >
                                Edit
                              </Link>
                              <StatusToggle
                                name={variant.variantName}
                                status={variant.status}
                                onChange={(next) => variantApi.setStatus(variant.id, next)}
                                onDone={list.reload}
                                deactivateHint={deactivateHints.variant}
                              />
                            </div>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </ListBody>
          {list.data && (
            <Pagination
              page={list.data.page}
              size={list.data.size}
              totalPages={list.data.totalPages}
              totalElements={list.data.totalElements}
              onPageChange={(next) => updateParams({ page: String(next) })}
            />
          )}
        </div>
      </Card>
    </div>
  )
}
