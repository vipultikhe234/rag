import { useState } from 'react'
import { Link } from 'react-router'
import { brandApi, categoryApi, productApi, variantApi } from '../../api/endpoints'
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
const MAX_OPTION_FILTERS = 5
const OPTION_FILTER = /^[^:]{1,50}:.{1,100}$/

/** "Color:Black, Storage:256" -> ["Color:Black", "Storage:256"], or an error message. */
function parseOptionFilters(text: string): { filters: string[]; error?: string } {
  const filters = text
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part !== '')
    .map((part) => {
      const index = part.indexOf(':')
      return index < 0 ? part : `${part.slice(0, index).trim()}:${part.slice(index + 1).trim()}`
    })
  if (filters.length > MAX_OPTION_FILTERS) return { filters, error: `At most ${MAX_OPTION_FILTERS} option filters` }
  const invalid = filters.find((filter) => !OPTION_FILTER.test(filter))
  return invalid ? { filters, error: `Use Name:Value, e.g. Color:Black` } : { filters }
}
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
  const categoryId = params.get('categoryId') ?? ''
  const brandId = params.get('brandId') ?? ''
  const stock = (params.get('stock') ?? '') as StockFilter | ''
  const minPrice = params.get('minPrice') ?? ''
  const maxPrice = params.get('maxPrice') ?? ''
  const option = params.getAll('option')
  const sort = params.get('sort') ?? 'createdAt,desc'
  const [searchInput, setSearchInput] = useDebouncedSearch(q, updateParams)
  const [minInput, setMinInput] = useState(minPrice)
  const [maxInput, setMaxInput] = useState(maxPrice)
  const [optionInput, setOptionInput] = useState(option.join(', '))

  const products = useLoader(
    (signal) => productApi.list({ hasVariants: true, page: 0, size: 100, sort: 'name,asc' }, signal),
    'variant-products',
  )
  const categories = useLoader((signal) => categoryApi.options(true, signal), 'category-options')
  const brands = useLoader((signal) => brandApi.options(true, signal), 'brand-options')
  const list = useLoader(
    (signal) =>
      variantApi.list(
        { q, status, productId, categoryId, brandId, stock, minPrice, maxPrice, option, page, size: PAGE_SIZE, sort },
        signal,
      ),
    JSON.stringify([q, status, productId, categoryId, brandId, stock, minPrice, maxPrice, option, sort, page]),
  )
  const filtersActive = Boolean(
    q || status || productId || categoryId || brandId || stock || minPrice || maxPrice || option.length > 0,
  )
  const selectedProduct = products.data?.content.find((p) => p.id === productId)
  const priceInvalid = !PRICE.test(minInput.trim()) || !PRICE.test(maxInput.trim())
  const priceInverted =
    !priceInvalid && minInput.trim() !== '' && maxInput.trim() !== '' && Number(minInput) > Number(maxInput)
  const optionParse = parseOptionFilters(optionInput)

  const applyPrices = () => {
    if (priceInvalid || priceInverted) return
    updateParams({ minPrice: minInput.trim() || null, maxPrice: maxInput.trim() || null })
  }
  const applyOptions = () => {
    if (optionParse.error) return
    updateParams({ option: optionParse.filters })
  }

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader
        title={selectedProduct ? `Variants of ${selectedProduct.name}` : 'Product variants'}
        description="Sellable combinations of a product's options, such as colour and storage, each with its own SKU, price and stock. Variants are deactivated, never deleted."
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
          className="grid gap-4 md:grid-cols-3 xl:grid-cols-5"
          role="search"
          aria-label="Filter variants"
          onSubmit={(event) => {
            event.preventDefault()
            updateParams({ q: searchInput.trim() || null })
            applyPrices()
            applyOptions()
          }}
        >
          <TextField
            label="Search"
            type="search"
            placeholder="SKU, variant, product or barcode"
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
          <SelectField
            label="Category"
            hint="Includes subcategories."
            value={categoryId}
            onChange={(event) => updateParams({ categoryId: event.target.value })}
          >
            <option value="">All categories</option>
            {(categories.data ?? []).map((category) => (
              <option key={category.id} value={category.id}>
                {category.path}
              </option>
            ))}
          </SelectField>
          <SelectField label="Brand" value={brandId} onChange={(event) => updateParams({ brandId: event.target.value })}>
            <option value="">All brands</option>
            {(brands.data ?? []).map((brand) => (
              <option key={brand.id} value={brand.id}>
                {brand.name}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Option values"
            placeholder="Color:Black, Storage:256"
            value={optionInput}
            maxLength={600}
            error={optionParse.error}
            hint={optionParse.error ? undefined : 'Name:Value pairs, comma-separated (up to 5).'}
            onChange={(event) => setOptionInput(event.target.value)}
            onBlur={applyOptions}
          />
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
            error={
              !PRICE.test(maxInput.trim())
                ? 'Invalid amount'
                : priceInverted
                  ? 'Must be at least the min price'
                  : undefined
            }
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
              setOptionInput('')
              updateParams({
                q: null,
                status: null,
                productId: null,
                categoryId: null,
                brandId: null,
                stock: null,
                minPrice: null,
                maxPrice: null,
                option: null,
              })
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
                      <th scope="col" className={table.th}>Category / brand</th>
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
                        <td className={`${table.td} text-slate-600`}>
                          {variant.category ?? '—'}
                          {variant.brand && <div className="text-xs text-slate-500">{variant.brand}</div>}
                        </td>
                        <td className={`${table.td} whitespace-nowrap text-right tabular-nums`}>
                          {formatPrice(variant.price, variant.currency)}
                          {variant.compareAtPrice !== null && (
                            <div className="text-xs text-slate-400 line-through">
                              {formatPrice(variant.compareAtPrice, variant.currency)}
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
