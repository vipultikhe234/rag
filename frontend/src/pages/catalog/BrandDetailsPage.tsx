import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { brandApi } from '../../api/endpoints'
import { normalizeError } from '../../api/errors'
import { useAuth } from '../../auth/authContext'
import { useToast } from '../../components/toast/toastContext'
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
import { formatDateTime } from '../../lib/format'

function ExternalLink({ href }: { href: string }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" className={`break-all ${linkClass.text}`}>
      {href}
    </a>
  )
}

export function BrandDetailsPage() {
  const { id = '' } = useParams()
  const { isAdmin } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const brand = useLoader((signal) => brandApi.get(id, signal), id)

  const remove = async () => {
    setDeleting(true)
    try {
      await brandApi.remove(id)
      toast.success(`Brand ${brand.data?.name} deleted.`)
      navigate('/brands')
    } catch (err) {
      toast.error(normalizeError(err).message)
      setConfirmDelete(false)
    } finally {
      setDeleting(false)
    }
  }

  if (brand.loading) return <LoadingBlock label="Loading brand" />
  if (brand.error || !brand.data) {
    return (
      <div className="mx-auto max-w-4xl space-y-4">
        <PageHeader title="Brand" back={{ to: '/brands', label: 'Back to brands' }} />
        <ErrorState message={brand.error ?? 'The brand was not found.'} onRetry={brand.reload} />
      </div>
    )
  }

  const b = brand.data
  const inUse = b.productCount > 0

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <PageHeader
        title={
          <span className="flex items-center gap-3">
            {b.name} <StatusBadge status={b.status} />
          </span>
        }
        description={<span className="font-mono">{b.slug}</span>}
        back={{ to: '/brands', label: 'Back to brands' }}
        actions={
          isAdmin && (
            <>
              <Link to={`/brands/${b.id}/edit`} className={linkClass.secondary}>
                Edit
              </Link>
              <StatusToggle
                name={b.name}
                status={b.status}
                size="md"
                variant="secondary"
                onChange={(next) => brandApi.setStatus(b.id, next)}
                onDone={brand.reload}
                deactivateHint={deactivateHints.brand}
              />
              <Button
                variant="secondary"
                className="text-red-600"
                disabled={inUse}
                title={inUse ? 'Only unused brands can be deleted; deactivate it instead.' : undefined}
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
          <DetailItem label="Website">{b.websiteUrl ? <ExternalLink href={b.websiteUrl} /> : null}</DetailItem>
          <DetailItem label="Products">
            <Link to={`/products?brandId=${b.id}`} className={linkClass.text}>
              {b.productCount} {b.productCount === 1 ? 'product' : 'products'}
            </Link>
          </DetailItem>
          <DetailItem label="Description" wide>
            {b.description}
          </DetailItem>
          <DetailItem label="Logo URL" wide>
            {b.logoUrl ? <ExternalLink href={b.logoUrl} /> : null}
          </DetailItem>
          <DetailItem label="Created">{formatDateTime(b.createdAt)}</DetailItem>
          <DetailItem label="Last updated">{formatDateTime(b.updatedAt)}</DetailItem>
        </DetailList>
      </Card>

      <ConfirmDialog
        open={confirmDelete}
        title="Delete brand?"
        confirmLabel="Delete brand"
        busy={deleting}
        onConfirm={() => void remove()}
        onCancel={() => setConfirmDelete(false)}
      >
        <p>
          <strong>{b.name}</strong> is not used by any product and will be permanently deleted.
        </p>
      </ConfirmDialog>
    </div>
  )
}
