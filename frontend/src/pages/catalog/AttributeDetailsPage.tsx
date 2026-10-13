import { Link, useParams } from 'react-router'
import { attributeApi } from '../../api/endpoints'
import { useAuth } from '../../auth/authContext'
import { Badge } from '../../components/ui/Badge'
import { Card } from '../../components/ui/Card'
import { LoadingBlock } from '../../components/ui/Spinner'
import { ErrorState } from '../../components/ui/States'
import { DetailItem, DetailList, PageHeader, StatusToggle, YesNoBadge } from '../../features/catalog/CatalogUi'
import { useLoader } from '../../features/catalog/hooks'
import { deactivateHints } from '../../features/catalog/messages'
import { linkClass } from '../../features/catalog/styles'
import { StatusBadge } from '../../features/products/badges'
import { formatDateTime, humanize } from '../../lib/format'

export function AttributeDetailsPage() {
  const { id = '' } = useParams()
  const { isAdmin } = useAuth()
  const attribute = useLoader((signal) => attributeApi.get(id, signal), id)

  if (attribute.loading) return <LoadingBlock label="Loading attribute" />
  if (attribute.error || !attribute.data) {
    return (
      <div className="mx-auto max-w-4xl space-y-4">
        <PageHeader title="Attribute" back={{ to: '/product-attributes', label: 'Back to attributes' }} />
        <ErrorState message={attribute.error ?? 'The attribute was not found.'} onRetry={attribute.reload} />
      </div>
    )
  }

  const a = attribute.data

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <PageHeader
        title={
          <span className="flex items-center gap-3">
            {a.name} <StatusBadge status={a.status} />
          </span>
        }
        description={<span className="font-mono">{a.slug}</span>}
        back={{ to: '/product-attributes', label: 'Back to attributes' }}
        actions={
          isAdmin && (
            <>
              <Link to={`/product-attributes/${a.id}/edit`} className={linkClass.secondary}>
                Edit
              </Link>
              <StatusToggle
                name={a.name}
                status={a.status}
                size="md"
                variant="secondary"
                onChange={(next) => attributeApi.setStatus(a.id, next)}
                onDone={attribute.reload}
                deactivateHint={deactivateHints.attribute}
              />
            </>
          )
        }
      />

      <Card title="Definition">
        <DetailList>
          <DetailItem label={a.categories.length === 1 ? 'Category' : `Categories (${a.categories.length})`}>
            <span className="flex flex-wrap gap-x-3 gap-y-1">
              {a.categories.map((category) => (
                <span key={category.id}>
                  <Link to={`/categories/${category.id}`} className={linkClass.text}>
                    {category.name}
                  </Link>
                  {!category.active && <span className="text-xs text-slate-500"> (inactive)</span>}
                </span>
              ))}
            </span>
            <span className="block text-xs text-slate-500">Also applies to their subcategories.</span>
          </DetailItem>
          <DetailItem label="Data type">
            {humanize(a.dataType)}
            {a.unit && <span className="text-slate-500"> · unit {a.unit}</span>}
          </DetailItem>
          <DetailItem label="Rules">
            <span className="flex flex-wrap gap-1">
              <YesNoBadge value={a.required} yes="Required" no="Optional" />
              <YesNoBadge value={a.filterable} yes="Filterable" no="Not filterable" />
            </span>
          </DetailItem>
          <DetailItem label="Product data key">
            <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">attributes.{a.slug}</code>
          </DetailItem>
          {a.dataType === 'SELECT' && (
            <DetailItem label={`Options (${a.options.length})`} wide>
              <span className="flex flex-wrap gap-1.5">
                {a.options.map((option) => (
                  <Badge key={option}>{option}</Badge>
                ))}
              </span>
            </DetailItem>
          )}
          <DetailItem label="Created">{formatDateTime(a.createdAt)}</DetailItem>
          <DetailItem label="Last updated">{formatDateTime(a.updatedAt)}</DetailItem>
        </DetailList>
      </Card>

      {a.filterable && a.status === 'ACTIVE' && a.categories.length > 0 && (
        <p className="text-sm text-slate-500">
          The product list can filter products in{' '}
          {a.categories.map((category, index) => (
            <span key={category.id}>
              {index > 0 && ', '}
              <Link to={`/products?categoryId=${category.id}`} className={linkClass.text}>
                {category.name}
              </Link>
            </span>
          ))}{' '}
          by this attribute.
        </p>
      )}
    </div>
  )
}
