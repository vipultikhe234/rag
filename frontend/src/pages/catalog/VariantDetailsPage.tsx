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
import { formatDateTime, formatPrice } from '../../lib/format'

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
        back={{ to: `/product-variants?productId=${v.productId}`, label: `Back to ${v.productName} variants` }}
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
            {isAdmin ? (
              <Link to={`/products/${v.productId}/edit`} className={linkClass.text}>
                {v.productName}
              </Link>
            ) : (
              v.productName
            )}
            <span className="ml-2 font-mono text-xs text-slate-500">{v.productSku}</span>
            {v.productStatus === 'INACTIVE' && (
              <span className="ml-2">
                <Badge tone="gray">Product inactive</Badge>
              </span>
            )}
          </DetailItem>
          <DetailItem label="Price">
            <span className="tabular-nums">{formatPrice(v.effectivePrice, v.currency)}</span>
            {v.salePrice !== null && (
              <span className="ml-2 text-xs text-slate-500">
                on sale, regular <span className="line-through">{formatPrice(v.price, v.currency)}</span>
              </span>
            )}
          </DetailItem>
          <DetailItem label="Stock">
            <span className="mr-2 tabular-nums">{v.stockQuantity}</span>
            <StockBadge level={v.stockLevel} />
          </DetailItem>
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
