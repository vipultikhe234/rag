import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { normalizeError } from '../../api/errors'
import type { CatalogStatus, StockLevel } from '../../api/types'
import { useToast } from '../../components/toast/toastContext'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { LoadingBlock } from '../../components/ui/Spinner'
import { EmptyState, ErrorState, InlineAlert } from '../../components/ui/States'
import { linkClass } from './styles'

interface PageHeaderProps {
  title: ReactNode
  description?: ReactNode
  back?: { to: string; label: string }
  actions?: ReactNode
}

export function PageHeader({ title, description, back, actions }: PageHeaderProps) {
  return (
    <div className="space-y-2">
      {back && (
        <Link to={back.to} className={`text-sm ${linkClass.text}`}>
          ← {back.label}
        </Link>
      )}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>
          {description && <p className="text-sm text-slate-500">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  )
}

export function DetailList({ children }: { children: ReactNode }) {
  return <dl className="grid gap-x-6 gap-y-4 sm:grid-cols-2">{children}</dl>
}

export function DetailItem({ label, children, wide = false }: { label: string; children: ReactNode; wide?: boolean }) {
  return (
    <div className={wide ? 'sm:col-span-2' : undefined}>
      <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-1 text-sm text-slate-900">{children ?? '—'}</dd>
    </div>
  )
}

export function YesNoBadge({ value, yes, no }: { value: boolean; yes: string; no?: string }) {
  if (!value && !no) return null
  return <Badge tone={value ? 'indigo' : 'gray'}>{value ? yes : no}</Badge>
}

export function StockBadge({ level }: { level: StockLevel }) {
  switch (level) {
    case 'OUT_OF_STOCK':
      return <Badge tone="red">Out of stock</Badge>
    case 'LOW_STOCK':
      return <Badge tone="amber">Low stock</Badge>
    default:
      return <Badge tone="green">In stock</Badge>
  }
}

interface StatusToggleProps {
  name: string
  status: CatalogStatus
  onChange: (next: CatalogStatus) => Promise<unknown>
  onDone: () => void
  /** Explains what deactivation means for this kind of record. */
  deactivateHint: ReactNode
  size?: 'sm' | 'md'
  variant?: 'ghost' | 'secondary'
}

/** Activate immediately; deactivate after a confirmation dialog. Shows a toast either way. */
export function StatusToggle({ name, status, onChange, onDone, deactivateHint, size = 'sm', variant = 'ghost' }: StatusToggleProps) {
  const toast = useToast()
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const next: CatalogStatus = status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'

  const apply = async () => {
    setBusy(true)
    try {
      await onChange(next)
      toast.success(`${name} ${next === 'ACTIVE' ? 'activated' : 'deactivated'}.`)
      setConfirming(false)
      onDone()
    } catch (err) {
      toast.error(normalizeError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Button
        variant={variant}
        size={size}
        loading={busy && !confirming}
        onClick={() => (next === 'INACTIVE' ? setConfirming(true) : void apply())}
        aria-label={`${next === 'ACTIVE' ? 'Activate' : 'Deactivate'} ${name}`}
      >
        {next === 'ACTIVE' ? 'Activate' : 'Deactivate'}
      </Button>
      <ConfirmDialog
        open={confirming}
        title={`Deactivate ${name}?`}
        confirmLabel="Deactivate"
        tone="primary"
        busy={busy}
        onConfirm={() => void apply()}
        onCancel={() => setConfirming(false)}
      >
        {deactivateHint}
      </ConfirmDialog>
    </>
  )
}

interface ListBodyProps<T> {
  loading: boolean
  error: string | null
  data: { content: T[] } | null
  onRetry: () => void
  label: string
  filtersActive: boolean
  onClearFilters: () => void
  emptyDescription: string
  children: (items: T[]) => ReactNode
}

/** Loading, error and empty states shared by the catalog list pages. */
export function ListBody<T>({
  loading,
  error,
  data,
  onRetry,
  label,
  filtersActive,
  onClearFilters,
  emptyDescription,
  children,
}: ListBodyProps<T>) {
  if (loading && !data) return <LoadingBlock label={`Loading ${label}`} />
  if (error && !data) return <ErrorState message={error} onRetry={onRetry} />
  if (!data) return null
  if (data.content.length === 0) {
    return (
      <EmptyState
        title={filtersActive ? `No ${label} match your filters` : `No ${label} yet`}
        description={filtersActive ? 'Try a different search term or clear the filters.' : emptyDescription}
        action={
          filtersActive ? (
            <Button variant="secondary" onClick={onClearFilters}>
              Clear filters
            </Button>
          ) : undefined
        }
      />
    )
  }
  return (
    <>
      {error && (
        <div className="mb-3">
          <InlineAlert tone="red">{error}</InlineAlert>
        </div>
      )}
      {children(data.content)}
    </>
  )
}