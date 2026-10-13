import { useCallback, useEffect, useState } from 'react'
import { vectorSyncApi } from '../../api/endpoints'
import { normalizeError } from '../../api/errors'
import type { VectorSyncStatusSummary } from '../../api/types'
import { useToast } from '../../components/toast/toastContext'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { LoadingBlock } from '../../components/ui/Spinner'
import { ErrorState, InlineAlert } from '../../components/ui/States'
import { formatDateTime, humanize } from '../../lib/format'

interface VectorSyncPanelProps {
  refreshKey: number
  onChanged: () => void
}

/** Admin view of the outbox: counters, recent failures and manual retry actions. */
export function VectorSyncPanel({ refreshKey, onChanged }: VectorSyncPanelProps) {
  const toast = useToast()
  const [status, setStatus] = useState<VectorSyncStatusSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [expanded, setExpanded] = useState(false)

  const load = useCallback(async () => {
    try {
      setStatus(await vectorSyncApi.status())
      setError(null)
    } catch (err) {
      setError(normalizeError(err).message)
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    vectorSyncApi
      .status()
      .then((result) => {
        if (cancelled) return
        setStatus(result)
        setError(null)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(normalizeError(err).message)
      })
    return () => {
      cancelled = true
    }
  }, [refreshKey])

  useEffect(() => {
    if (!status || (status.pending === 0 && status.processing === 0)) return
    const timer = window.setInterval(() => void load(), 5000)
    return () => window.clearInterval(timer)
  }, [status, load])

  const run = async (key: string, action: () => Promise<string>) => {
    setBusy(key)
    try {
      toast.success(await action())
      await load()
      onChanged()
    } catch (err) {
      toast.error(normalizeError(err).message)
    } finally {
      setBusy(null)
    }
  }

  if (error && !status) {
    return (
      <Card title="AI search index">
        <ErrorState message={error} onRetry={() => void load()} />
      </Card>
    )
  }
  if (!status) {
    return (
      <Card title="AI search index">
        <LoadingBlock label="Loading sync status" />
      </Card>
    )
  }

  const healthy = status.failed === 0 && status.activeProductsWithoutEmbedding === 0
  return (
    <Card
      title="AI search index"
      description={`Embedding model ${status.embeddingModel} (${status.embeddingDimensions} dimensions). Product changes are indexed asynchronously.`}
      actions={
        <>
          <Button variant="ghost" size="sm" onClick={() => setExpanded((open) => !open)} aria-expanded={expanded}>
            {expanded ? 'Hide details' : 'Show details'}
          </Button>
          <Button variant="secondary" size="sm" onClick={() => void load()}>
            Refresh
          </Button>
        </>
      }
    >
      {!status.aiProviderConfigured && (
        <div className="mb-3">
          <InlineAlert tone="amber">
            No AI provider is configured (OPENAI_API_KEY is not set). Product changes are queued and will be indexed
            once the key is configured and the backend is restarted. AI Chat is unavailable until then.
          </InlineAlert>
        </div>
      )}
      {!status.workerEnabled && (
        <div className="mb-3">
          <InlineAlert tone="amber">The background sync worker is disabled (VECTOR_SYNC_ENABLED=false).</InlineAlert>
        </div>
      )}

      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="Indexed products" value={status.embeddedProducts} />
        <Stat label="Not indexed (active)" value={status.activeProductsWithoutEmbedding} warn={status.activeProductsWithoutEmbedding > 0} />
        <Stat label="Pending" value={status.pending} />
        <Stat label="Processing" value={status.processing} />
        <Stat label="Failed (retrying)" value={status.failedRetryable} warn={status.failedRetryable > 0} />
        <Stat label="Failed (needs action)" value={status.failedPermanent} danger={status.failedPermanent > 0} />
      </dl>

      {healthy && status.pending === 0 && (
        <p className="mt-3 text-sm text-emerald-700">All active products are indexed.</p>
      )}

      {expanded && (
        <div className="mt-4 space-y-3">
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="secondary"
              loading={busy === 'retry-all'}
              disabled={status.failed === 0}
              onClick={() =>
                void run('retry-all', async () => {
                  const result = await vectorSyncApi.retryAllFailed()
                  return `${result.requeued} failed event(s) re-queued.`
                })
              }
            >
              Retry all failed
            </Button>
            <Button
              size="sm"
              variant="secondary"
              loading={busy === 'resync'}
              onClick={() =>
                void run('resync', async () => {
                  const result = await vectorSyncApi.resyncMissing()
                  return `${result.enqueued} product(s) queued for indexing.`
                })
              }
            >
              Index missing products
            </Button>
            {status.oldestPendingAt && (
              <span className="self-center text-xs text-slate-500">
                Oldest pending since {formatDateTime(status.oldestPendingAt)}
              </span>
            )}
          </div>

          {status.recentFailures.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-sm">
                <caption className="sr-only">Recent vector sync failures</caption>
                <thead className="bg-slate-50 text-left text-xs font-semibold uppercase tracking-wide text-slate-500">
                  <tr>
                    <th scope="col" className="px-3 py-2">Product</th>
                    <th scope="col" className="px-3 py-2">Event</th>
                    <th scope="col" className="px-3 py-2">Attempts</th>
                    <th scope="col" className="px-3 py-2">Error</th>
                    <th scope="col" className="px-3 py-2">Next attempt</th>
                    <th scope="col" className="px-3 py-2"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {status.recentFailures.map((event) => (
                    <tr key={event.id}>
                      <td className="px-3 py-2">
                        <div className="font-medium text-slate-900">{event.productName ?? '—'}</div>
                        <div className="text-xs text-slate-500">{event.productSku ?? event.productId}</div>
                      </td>
                      <td className="px-3 py-2">{humanize(event.eventType)}</td>
                      <td className="px-3 py-2">
                        {event.attempts}/{event.maxAttempts}
                      </td>
                      <td className="max-w-md px-3 py-2 text-xs text-red-700">{event.lastError ?? '—'}</td>
                      <td className="px-3 py-2 text-xs">
                        {event.retryable ? formatDateTime(event.nextAttemptAt) : 'Manual retry required'}
                      </td>
                      <td className="px-3 py-2 text-right">
                        <Button
                          size="sm"
                          variant="secondary"
                          loading={busy === event.id}
                          onClick={() =>
                            void run(event.id, async () => {
                              await vectorSyncApi.retry(event.id)
                              return 'Event re-queued for processing.'
                            })
                          }
                        >
                          Retry now
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-sm text-slate-500">No failed sync events.</p>
          )}
        </div>
      )}
    </Card>
  )
}

function Stat({ label, value, warn, danger }: { label: string; value: number; warn?: boolean; danger?: boolean }) {
  const tone = danger ? 'text-red-700' : warn ? 'text-amber-700' : 'text-slate-900'
  return (
    <div className="rounded-md bg-slate-50 px-3 py-2">
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className={`text-lg font-semibold ${tone}`}>{value}</dd>
    </div>
  )
}
