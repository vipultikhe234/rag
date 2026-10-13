import { Link, useParams } from 'react-router'
import { variantApi } from '../../api/endpoints'
import { useAuth } from '../../auth/authContext'
import { Badge } from '../../components/ui/Badge'
import { Card } from '../../components/ui/Card'
import { LoadingBlock } from '../../components/ui/Spinner'
import { ErrorState } from '../../components/ui/States'
import { formatAttributeValue } from '../../features/catalog/attributeValues'
import { DetailItem, DetailList, PageHeader, StatusToggle, StockBadge } from '../../features/catalog/CatalogUi'
import { useLoader } from '../../features/catalog/hooks'
import { deactivateHints } from '../../features/catalog/messages'
import { linkClass } from '../../features/catalog/styles'
import { StatusBadge } from '../../features/products/badges'
import { SalePrice } from '../../features/products/Price'
import { formatDateTime } from '../../lib/format'

export function VariantDetailsPage() {
  const { id = '' } = useParams()
  const { isAdmin } = useAuth()
  const variant = useLoader((signal) => variantApi.get(id, signal), id)

  if (variant.loading) return <LoadingBlock label="Loading variant" />
  if (variant.error || !variant.data) {
    return (
      <div className="mx-auto max-w-4xl space-y-4">
        <PageHeader title="Variant" back={{ to: '/product-variants', label: 'Back to variants' }} />
        <ErrorState message={variant.error ?? 'The variant was not found.'} onRetry={variant.reload} />
      </div>
    )
  }

  const v = variant.data
  const attributes = Object.entries(v.attributes ?? {})

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <PageHeader
        title={
          <span className="flex items-center gap-3">
            {v.variantName} <StatusBadge status={v.status} />
          </span>
        }
        description={<span className="font-mono">{v.sku}</span>}
        back={{ to: `/products/${v.productId}?tab=variants`, label: `Back to ${v.productName} variants` }}
        actions={
          isAdmin && (
            <>
              <Link to={`/product-variants/${v.id}/edit`} className={linkClass.secondary}>
                Edit
              </Link>
              <StatusToggle
                name={v.variantName}
                status={v.status}
                size="md"
                variant="secondary"
                onChange={(next) => variantApi.setStatus(v.id, next)}
                onDone={variant.reload}
                deactivateHint={deactivateHints.variant}
              />
            </>
          )
        }
      />

      <Card title="Details">
        <DetailList>
          <DetailItem label="Product">
            <Link to={`/products/${v.productId}`} className={linkClass.text}>
              {v.productName}
            </Link>
            <span className="ml-2 font-mono text-xs text-slate-500">{v.productSku}</span>
            {v.productStatus === 'INACTIVE' && (
              <span className="ml-2">
                <Badge tone="gray">Product inactive</Badge>
              </span>
            )}
          </DetailItem>
          <DetailItem label="Options">
            {v.options.length === 0 ? (
              '—'
            ) : (
              <span className="flex flex-wrap gap-1.5">
                {v.options.map((option) => (
                  <Badge key={option.optionId}>
                    {option.name}: {option.label}
                  </Badge>
                ))}
              </span>
            )}
          </DetailItem>
          <DetailItem label="Price">
            <SalePrice price={v.price} compareAtPrice={v.compareAtPrice} currency={v.currency} />
            {v.onSale && (
              <span className="ml-2">
                <Badge tone="green">On sale</Badge>
              </span>
            )}
          </DetailItem>
          <DetailItem label="Stock">
            <span className="mr-2 tabular-nums">{v.stockQuantity}</span>
            <StockBadge level={v.stockLevel} />
          </DetailItem>
          <DetailItem label="Barcode">{v.barcode ? <span className="font-mono">{v.barcode}</span> : '—'}</DetailItem>
          <DetailItem label="Category / brand">
            {v.category ?? '—'}
            {v.brand && <span className="text-slate-500"> · {v.brand}</span>}
          </DetailItem>
          {v.imageUrl && (
            <DetailItem label="Image" wide>
              <img
                src={v.imageUrl}
                alt={v.variantName}
                className="h-32 w-32 rounded-md border border-slate-200 object-cover"
                referrerPolicy="no-referrer"
              />
            </DetailItem>
          )}
          <DetailItem label="Created">{formatDateTime(v.createdAt)}</DetailItem>
          <DetailItem label="Last updated">{formatDateTime(v.updatedAt)}</DetailItem>
        </DetailList>
      </Card>

      <Card title="Attributes">
        {attributes.length === 0 ? (
          <p className="text-sm text-slate-500">This variant has no attributes of its own.</p>
        ) : (
          <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
            {attributes.map(([key, value]) => (
              <div key={key}>
                <dt className="font-mono text-xs text-slate-500">{key}</dt>
                <dd className="text-sm text-slate-900">{formatAttributeValue(value)}</dd>
              </div>
            ))}
          </dl>
        )}
      </Card>
    </div>
  )
}
