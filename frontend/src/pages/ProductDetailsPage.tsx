import { Link, useParams, useSearchParams } from 'react-router'
import { attributeApi, productApi, variantApi } from '../api/endpoints'
import type { Product, ProductAttribute, ProductVariant } from '../api/types'
import { useAuth } from '../auth/authContext'
import { Badge } from '../components/ui/Badge'
import { Card } from '../components/ui/Card'
import { LoadingBlock } from '../components/ui/Spinner'
import { EmptyState, ErrorState } from '../components/ui/States'
import { formatAttributeValue } from '../features/catalog/attributeValues'
import { DetailItem, DetailList, PageHeader, StatusToggle, StockBadge } from '../features/catalog/CatalogUi'
import { useLoader } from '../features/catalog/hooks'
import { deactivateHints } from '../features/catalog/messages'
import { linkClass, table } from '../features/catalog/styles'
import { StatusBadge, SyncBadge } from '../features/products/badges'
import { PriceRange, SalePrice } from '../features/products/Price'
import { optionLabel } from '../features/products/variants'
import { formatDateTime, formatPrice } from '../lib/format'

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'specifications', label: 'Specifications' },
  { id: 'images', label: 'Images' },
  { id: 'variants', label: 'Variants' },
  { id: 'inventory', label: 'Inventory' },
] as const
type TabId = (typeof TABS)[number]['id']

const NO_VARIANTS: ProductVariant[] = []

function OverviewTab({ product }: { product: Product }) {
  return (
    <div className="space-y-6">
      <Card title="Overview">
        <DetailList>
          <DetailItem label="SKU">
            <span className="font-mono">{product.sku}</span>
          </DetailItem>
          <DetailItem label="Slug">
            <span className="font-mono">{product.slug}</span>
          </DetailItem>
          <DetailItem label="Category">
            {product.categoryId ? (
              <Link to={`/categories/${product.categoryId}`} className={linkClass.text}>
                {product.category}
              </Link>
            ) : (
              product.category
            )}
          </DetailItem>
          <DetailItem label="Brand">
            {product.brandId ? (
              <Link to={`/brands/${product.brandId}`} className={linkClass.text}>
                {product.brand}
              </Link>
            ) : (
              'No brand'
            )}
          </DetailItem>
          <DetailItem label="Type">{product.hasVariants ? 'Product with variants' : 'Simple product'}</DetailItem>
          <DetailItem label={product.hasVariants ? 'Price range (active variants)' : 'Price'}>
            {product.hasVariants ? (
              <PriceRange
                min={product.variantSummary.minPrice}
                max={product.variantSummary.maxPrice}
                currency={product.currency}
              />
            ) : (
              <span className="tabular-nums">{formatPrice(product.price, product.currency)}</span>
            )}
          </DetailItem>
          <DetailItem label={product.hasVariants ? 'Total stock (active variants)' : 'Stock'}>
            <span className="tabular-nums">{product.stockQuantity}</span>
          </DetailItem>
          {product.hasVariants && (
            <DetailItem label="Variants">
              {product.variantSummary.activeCount} active of {product.variantSummary.count}
            </DetailItem>
          )}
          <DetailItem label="AI index">
            <SyncBadge sync={product.vectorSync} />
          </DetailItem>
          <DetailItem label="Created">{formatDateTime(product.createdAt)}</DetailItem>
          <DetailItem label="Last updated">{formatDateTime(product.updatedAt)}</DetailItem>
          <DetailItem label="Description" wide>
            <span className="whitespace-pre-line">{product.description}</span>
          </DetailItem>
        </DetailList>
      </Card>
      {product.hasVariants && product.options.length > 0 && (
        <Card title="Options">
          <DetailList>
            {product.options.map((option) => (
              <DetailItem key={option.id} label={option.name}>
                <span className="flex flex-wrap gap-1.5">
                  {option.values.map((value) => (
                    <Badge key={value.id}>{optionLabel(value.value, option.unit)}</Badge>
                  ))}
                </span>
              </DetailItem>
            ))}
          </DetailList>
        </Card>
      )}
    </div>
  )
}

function SpecificationsTab({ product }: { product: Product }) {
  const definitions = useLoader(
    (signal) =>
      product.categoryId ? attributeApi.applicable(product.categoryId, true, signal) : Promise.resolve<ProductAttribute[]>([]),
    product.categoryId ?? '',
  )
  if (definitions.loading) return <LoadingBlock label="Loading specifications" />
  const bySlug = new Map((definitions.data ?? []).map((definition) => [definition.slug, definition]))
  const entries = Object.entries(product.attributes ?? {})
  const typed = entries.filter(([key]) => bySlug.has(key))
  const other = entries.filter(([key]) => !bySlug.has(key))
  const missingRequired = (definitions.data ?? []).filter(
    (definition) => definition.required && definition.status === 'ACTIVE' && !(definition.slug in (product.attributes ?? {})),
  )

  return (
    <div className="space-y-6">
      {definitions.error && <ErrorState message={definitions.error} onRetry={definitions.reload} />}
      <Card title="Specifications" description="Attributes defined for the product's category and its parent categories.">
        {typed.length === 0 ? (
          <p className="text-sm text-slate-500">No specifications are filled in.</p>
        ) : (
          <DetailList>
            {typed.map(([key, value]) => {
              const definition = bySlug.get(key)!
              return (
                <DetailItem key={key} label={definition.unit ? `${definition.name} (${definition.unit})` : definition.name}>
                  {formatAttributeValue(value)}
                  {definition.status !== 'ACTIVE' && (
                    <span className="ml-2">
                      <Badge tone="gray">Inactive attribute</Badge>
                    </span>
                  )}
                </DetailItem>
              )
            })}
          </DetailList>
        )}
        {missingRequired.length > 0 && (
          <p className="mt-4 text-sm text-amber-700">
            Missing required: {missingRequired.map((definition) => definition.name).join(', ')}. They are enforced the next
            time the product is saved.
          </p>
        )}
      </Card>
      {other.length > 0 && (
        <Card title="Additional attributes">
          <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
            {other.map(([key, value]) => (
              <div key={key}>
                <dt className="text-xs font-medium text-slate-500">{key}</dt>
                <dd className="text-sm text-slate-900">{formatAttributeValue(value)}</dd>
              </div>
            ))}
          </dl>
        </Card>
      )}
    </div>
  )
}

function ImagesTab({ product }: { product: Product }) {
  if (product.images.length === 0) {
    return <EmptyState title="No images" description="Add image URLs in the product form." />
  }
  return (
    <Card title={`Images (${product.images.length})`}>
      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {product.images.map((image, index) => (
          <li key={image.id} className="space-y-2">
            <a href={image.url} target="_blank" rel="noreferrer noopener" className="block">
              <img
                src={image.url}
                alt={image.altText ?? `${product.name} image ${index + 1}`}
                className="aspect-square w-full rounded-md border border-slate-200 object-cover"
                referrerPolicy="no-referrer"
                loading="lazy"
              />
            </a>
            <p className="text-xs text-slate-500">
              {index === 0 && <Badge tone="indigo">Main</Badge>} {image.altText ?? 'No alt text'}
            </p>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function VariantsTab({
  product,
  variants,
  loading,
  error,
  reload,
  isAdmin,
}: {
  product: Product
  variants: ProductVariant[]
  loading: boolean
  error: string | null
  reload: () => void
  isAdmin: boolean
}) {
  if (!product.hasVariants) {
    return (
      <EmptyState
        title="Simple product"
        description="This product is sold as a single SKU. Turn on variants in the product form to sell options such as colour or size."
        action={
          isAdmin ? (
            <Link to={`/products/${product.id}/edit`} className={linkClass.secondary}>
              Edit product
            </Link>
          ) : undefined
        }
      />
    )
  }
  if (loading && variants.length === 0) return <LoadingBlock label="Loading variants" />
  if (error) return <ErrorState message={error} onRetry={reload} />

  return (
    <Card
      title={`Variants (${variants.length})`}
      description="Variants are deactivated instead of deleted, so order history stays intact."
      actions={
        <>
          <Link to={`/product-variants?productId=${product.id}`} className={linkClass.row}>
            Open in variant list
          </Link>
          {isAdmin && (
            <Link to={`/product-variants/new?productId=${product.id}`} className={linkClass.primary}>
              Add variant
            </Link>
          )}
        </>
      }
    >
      {variants.length === 0 ? (
        <p className="text-sm text-slate-500">No variants yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className={table.table}>
            <caption className="sr-only">Variants of {product.name}</caption>
            <thead className={table.head}>
              <tr>
                <th scope="col" className={table.th}>Variant</th>
                <th scope="col" className={table.th}>Options</th>
                <th scope="col" className={`${table.th} text-right`}>Price</th>
                <th scope="col" className={`${table.th} text-right`}>Stock</th>
                <th scope="col" className={table.th}>Barcode</th>
                <th scope="col" className={table.th}>Status</th>
                {isAdmin && (
                  <th scope="col" className={`${table.th} text-right`}>
                    <span className="sr-only">Actions</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody className={table.body}>
              {variants.map((variant) => (
                <tr key={variant.id} className={table.row}>
                  <td className={table.td}>
                    <div className="flex items-center gap-2">
                      {variant.imageUrl && (
                        <img
                          src={variant.imageUrl}
                          alt=""
                          className="h-8 w-8 rounded border border-slate-200 object-cover"
                          referrerPolicy="no-referrer"
                        />
                      )}
                      <div>
                        <Link to={`/product-variants/${variant.id}`} className={linkClass.text}>
                          {variant.variantName}
                        </Link>
                        <div className="font-mono text-xs text-slate-500">{variant.sku}</div>
                      </div>
                    </div>
                  </td>
                  <td className={table.td}>
                    <span className="flex flex-wrap gap-1">
                      {variant.options.map((option) => (
                        <Badge key={option.optionId}>
                          {option.name}: {option.label}
                        </Badge>
                      ))}
                    </span>
                  </td>
                  <td className={`${table.td} whitespace-nowrap text-right`}>
                    <SalePrice price={variant.price} compareAtPrice={variant.compareAtPrice} currency={variant.currency} />
                  </td>
                  <td className={`${table.td} whitespace-nowrap text-right`}>
                    <span className="mr-2 tabular-nums">{variant.stockQuantity}</span>
                    <StockBadge level={variant.stockLevel} />
                  </td>
                  <td className={`${table.td} font-mono text-xs`}>{variant.barcode ?? '—'}</td>
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
                          onDone={reload}
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
    </Card>
  )
}

function InventoryTab({ product, variants }: { product: Product; variants: ProductVariant[] }) {
  if (!product.hasVariants) {
    return (
      <Card title="Inventory">
        <DetailList>
          <DetailItem label="Stock on hand">
            <span className="tabular-nums">{product.stockQuantity}</span>
            {product.stockQuantity === 0 && (
              <span className="ml-2">
                <Badge tone="red">Out of stock</Badge>
              </span>
            )}
          </DetailItem>
          <DetailItem label="Status">
            <StatusBadge status={product.status} />
          </DetailItem>
        </DetailList>
      </Card>
    )
  }
  const active = variants.filter((variant) => variant.status === 'ACTIVE')
  const count = (level: ProductVariant['stockLevel']) => active.filter((variant) => variant.stockLevel === level).length
  return (
    <div className="space-y-6">
      <Card title="Inventory summary" description="Totals count active variants only.">
        <DetailList>
          <DetailItem label="Total stock">
            <span className="tabular-nums">{product.stockQuantity}</span>
          </DetailItem>
          <DetailItem label="Active variants">
            {active.length} of {variants.length}
          </DetailItem>
          <DetailItem label="In stock / low / out">
            <span className="flex flex-wrap gap-1.5">
              <Badge tone="green">{count('IN_STOCK')} in stock</Badge>
              <Badge tone="amber">{count('LOW_STOCK')} low</Badge>
              <Badge tone="red">{count('OUT_OF_STOCK')} out</Badge>
            </span>
          </DetailItem>
        </DetailList>
      </Card>
      <Card title="Stock by variant">
        {variants.length === 0 ? (
          <p className="text-sm text-slate-500">No variants yet.</p>
        ) : (
          <table className={table.table}>
            <thead className={table.head}>
              <tr>
                <th scope="col" className={table.th}>Variant</th>
                <th scope="col" className={`${table.th} text-right`}>Stock</th>
                <th scope="col" className={table.th}>Level</th>
                <th scope="col" className={table.th}>Status</th>
              </tr>
            </thead>
            <tbody className={table.body}>
              {[...variants]
                .sort((a, b) => a.stockQuantity - b.stockQuantity)
                .map((variant) => (
                  <tr key={variant.id} className={table.row}>
                    <td className={table.td}>
                      {variant.variantName}
                      <div className="font-mono text-xs text-slate-500">{variant.sku}</div>
                    </td>
                    <td className={`${table.td} text-right tabular-nums`}>{variant.stockQuantity}</td>
                    <td className={table.td}>
                      <StockBadge level={variant.stockLevel} />
                    </td>
                    <td className={table.td}>
                      <StatusBadge status={variant.status} />
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  )
}

export function ProductDetailsPage() {
  const { id = '' } = useParams()
  const { isAdmin } = useAuth()
  const [params, setParams] = useSearchParams()
  const requested = params.get('tab')
  const tab: TabId = TABS.some((t) => t.id === requested) ? (requested as TabId) : 'overview'
  const product = useLoader((signal) => productApi.get(id, signal), id)
  const variants = useLoader(
    (signal) => (product.data?.hasVariants ? productApi.variants(id, signal) : Promise.resolve(NO_VARIANTS)),
    `${id}:${product.data?.hasVariants ? product.data.version : 'simple'}`,
  )

  if (product.loading && !product.data) return <LoadingBlock label="Loading product" />
  if (product.error || !product.data) {
    return (
      <div className="mx-auto max-w-5xl space-y-4">
        <PageHeader title="Product" back={{ to: '/products', label: 'Back to products' }} />
        <ErrorState message={product.error ?? 'The product was not found.'} onRetry={product.reload} />
      </div>
    )
  }

  const p = product.data
  const reloadAll = () => {
    product.reload()
    variants.reload()
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <PageHeader
        title={
          <span className="flex flex-wrap items-center gap-3">
            {p.name} <StatusBadge status={p.status} />
            {p.hasVariants && <Badge tone="indigo">{p.variantSummary.count} variants</Badge>}
          </span>
        }
        description={
          <span className="font-mono">
            {p.sku}
            {p.brand ? ` · ${p.brand}` : ''} · {p.category}
          </span>
        }
        back={{ to: '/products', label: 'Back to products' }}
        actions={
          isAdmin && (
            <>
              <Link to={`/products/${p.id}/edit`} className={linkClass.secondary}>
                Edit
              </Link>
              {p.hasVariants && (
                <Link to={`/products/${p.id}?tab=variants`} className={linkClass.secondary}>
                  Manage variants
                </Link>
              )}
              <StatusToggle
                name={p.name}
                status={p.status}
                size="md"
                variant="secondary"
                onChange={(next) => productApi.setStatus(p.id, next)}
                onDone={reloadAll}
                deactivateHint={deactivateHints.product}
              />
            </>
          )
        }
      />

      <div role="tablist" aria-label="Product sections" className="flex flex-wrap gap-1 border-b border-slate-200">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            id={`tab-${t.id}`}
            aria-selected={tab === t.id}
            aria-controls={`panel-${t.id}`}
            className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 ${
              tab === t.id ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-500 hover:text-slate-800'
            }`}
            onClick={() =>
              setParams(
                (current) => {
                  const next = new URLSearchParams(current)
                  if (t.id === 'overview') next.delete('tab')
                  else next.set('tab', t.id)
                  return next
                },
                { replace: true },
              )
            }
          >
            {t.label}
            {t.id === 'variants' && p.hasVariants ? ` (${p.variantSummary.count})` : ''}
            {t.id === 'images' ? ` (${p.images.length})` : ''}
          </button>
        ))}
      </div>

      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === 'overview' && <OverviewTab product={p} />}
        {tab === 'specifications' && <SpecificationsTab product={p} />}
        {tab === 'images' && <ImagesTab product={p} />}
        {tab === 'variants' && (
          <VariantsTab
            product={p}
            variants={variants.data ?? NO_VARIANTS}
            loading={variants.loading}
            error={variants.error}
            reload={reloadAll}
            isAdmin={isAdmin}
          />
        )}
        {tab === 'inventory' && <InventoryTab product={p} variants={variants.data ?? NO_VARIANTS} />}
      </div>
    </div>
  )
}
