import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { attributeApi, categoryApi } from '../../api/endpoints'
import { normalizeError } from '../../api/errors'
import { useAuth } from '../../auth/authContext'
import { useToast } from '../../components/toast/toastContext'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { LoadingBlock } from '../../components/ui/Spinner'
import { ErrorState } from '../../components/ui/States'
import { DetailItem, DetailList, PageHeader, StatusToggle } from '../../features/catalog/CatalogUi'
import { useLoader } from '../../features/catalog/hooks'
import { deactivateHints } from '../../features/catalog/messages'
import { linkClass } from '../../features/catalog/styles'
import { StatusBadge } from '../../features/products/badges'
import { formatDateTime, humanize } from '../../lib/format'

export function CategoryDetailsPage() {
  const { id = '' } = useParams()
  const { isAdmin } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const category = useLoader((signal) => categoryApi.get(id, signal), id)
  const children = useLoader(
    (signal) => categoryApi.list({ parentId: id, page: 0, size: 100, sort: 'name,asc' }, signal),
    id,
  )
  const attributes = useLoader(
    (signal) => attributeApi.list({ categoryId: id, page: 0, size: 100, sort: 'name,asc' }, signal),
    id,
  )

  const remove = async () => {
    setDeleting(true)
    try {
      await categoryApi.remove(id)
      toast.success(`Category ${category.data?.name} deleted.`)
      navigate('/categories')
    } catch (err) {
      toast.error(normalizeError(err).message)
      setConfirmDelete(false)
    } finally {
      setDeleting(false)
    }
  }

  if (category.loading) return <LoadingBlock label="Loading category" />
  if (category.error || !category.data) {
    return (
      <div className="mx-auto max-w-4xl space-y-4">
        <PageHeader title="Category" back={{ to: '/categories', label: 'Back to categories' }} />
        <ErrorState message={category.error ?? 'The category was not found.'} onRetry={category.reload} />
      </div>
    )
  }

  const c = category.data
  const inUse = c.productCount > 0 || c.subcategoryCount > 0 || c.attributeCount > 0

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <PageHeader
        title={
          <span className="flex items-center gap-3">
            {c.name} <StatusBadge status={c.status} />
          </span>
        }
        description={<span className="font-mono">{c.slug}</span>}
        back={{ to: '/categories', label: 'Back to categories' }}
        actions={
          isAdmin && (
            <>
              <Link to={`/categories/${c.id}/edit`} className={linkClass.secondary}>
                Edit
              </Link>
              <StatusToggle
                name={c.name}
                status={c.status}
                size="md"
                variant="secondary"
                onChange={(next) => categoryApi.setStatus(c.id, next)}
                onDone={category.reload}
                deactivateHint={deactivateHints.category}
              />
              <Button
                variant="secondary"
                className="text-red-600"
                disabled={inUse}
                title={inUse ? 'Only unused categories can be deleted; deactivate it instead.' : undefined}
                onClick={() => setConfirmDelete(true)}
              >
                Delete
              </Button>
            </>
          )
        }
      />

      <Card title="Details">
        <DetailList>
          <DetailItem label="Parent">
            {c.parentId ? (
              <Link to={`/categories/${c.parentId}`} className={linkClass.text}>
                {c.parentName}
              </Link>
            ) : (
              'None (top level)'
            )}
          </DetailItem>
          <DetailItem label="Products">
            <Link to={`/products?categoryId=${c.id}`} className={linkClass.text}>
              {c.productCount} directly assigned
            </Link>
          </DetailItem>
          <DetailItem label="Description" wide>
            {c.description}
          </DetailItem>
          {c.imageUrl && (
            <DetailItem label="Image URL" wide>
              <a href={c.imageUrl} target="_blank" rel="noopener noreferrer" className={`break-all ${linkClass.text}`}>
                {c.imageUrl}
              </a>
            </DetailItem>
          )}
          <DetailItem label="Created">{formatDateTime(c.createdAt)}</DetailItem>
          <DetailItem label="Last updated">{formatDateTime(c.updatedAt)}</DetailItem>
        </DetailList>
      </Card>

      <Card title={`Subcategories (${c.subcategoryCount})`}>
        {children.error ? (
          <ErrorState message={children.error} onRetry={children.reload} />
        ) : !children.data ? (
          <LoadingBlock label="Loading subcategories" />
        ) : children.data.content.length === 0 ? (
          <p className="text-sm text-slate-500">No subcategories.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {children.data.content.map((child) => (
              <li key={child.id}>
                <Link
                  to={`/categories/${child.id}`}
                  className="inline-flex items-center gap-2 rounded-md border border-slate-200 px-3 py-1.5 text-sm hover:bg-slate-50"
                >
                  {child.name}
                  {child.status === 'INACTIVE' && <Badge tone="gray">Inactive</Badge>}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card
        title={`Attributes defined here (${c.attributeCount})`}
        description="Subcategories inherit these attributes."
        actions={
          isAdmin && (
            <Link to={`/product-attributes/new?categoryId=${c.id}`} className={`text-sm ${linkClass.text}`}>
              Add attribute
            </Link>
          )
        }
      >
        {attributes.error ? (
          <ErrorState message={attributes.error} onRetry={attributes.reload} />
        ) : !attributes.data ? (
          <LoadingBlock label="Loading attributes" />
        ) : attributes.data.content.length === 0 ? (
          <p className="text-sm text-slate-500">No attributes are defined on this category.</p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {attributes.data.content.map((attribute) => (
              <li key={attribute.id} className="flex flex-wrap items-center gap-2 py-2 text-sm">
                <Link to={`/product-attributes/${attribute.id}`} className={linkClass.text}>
                  {attribute.name}
                </Link>
                <span className="text-slate-500">
                  {humanize(attribute.dataType)}
                  {attribute.unit ? ` · ${attribute.unit}` : ''}
                </span>
                {attribute.required && <Badge tone="indigo">Required</Badge>}
                {attribute.status === 'INACTIVE' && <Badge tone="gray">Inactive</Badge>}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <ConfirmDialog
        open={confirmDelete}
        title="Delete category?"
        confirmLabel="Delete category"
        busy={deleting}
        onConfirm={() => void remove()}
        onCancel={() => setConfirmDelete(false)}
      >
        <p>
          <strong>{c.name}</strong> is not used by any product, subcategory or attribute and will be permanently deleted.
        </p>
      </ConfirmDialog>
    </div>
  )
}
