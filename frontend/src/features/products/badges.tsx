import type { ProductStatus, ProductSyncInfo } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { formatDateTime } from '../../lib/format'

export function StatusBadge({ status }: { status: ProductStatus }) {
  return <Badge tone={status === 'ACTIVE' ? 'green' : 'gray'}>{status === 'ACTIVE' ? 'Active' : 'Inactive'}</Badge>
}

export function SyncBadge({ sync }: { sync: ProductSyncInfo | null }) {
  if (!sync) {
    return <Badge tone="gray">Unknown</Badge>
  }
  switch (sync.state) {
    case 'SYNCED':
      return (
        <Badge
          tone="green"
          title={`Embedded with ${sync.embeddingModel ?? 'unknown model'} at ${formatDateTime(sync.embeddedAt)}`}
        >
          Synced
        </Badge>
      )
    case 'PENDING':
      return (
        <Badge tone="blue" title="The search index is being updated">
          Pending
        </Badge>
      )
    case 'FAILED':
      return (
        <Badge
          tone={sync.autoRetryScheduled ? 'amber' : 'red'}
          title={
            sync.autoRetryScheduled
              ? `Will retry automatically at ${formatDateTime(sync.nextAttemptAt)}. ${sync.lastError ?? ''}`
              : `Sync failed and needs attention. ${sync.lastError ?? ''}`
          }
        >
          {sync.autoRetryScheduled ? 'Retrying' : 'Failed'}
        </Badge>
      )
    default:
      return (
        <Badge tone="gray" title="No embedding exists for this product yet">
          Not synced
        </Badge>
      )
  }
}
