import type { ReactNode } from 'react'
import { Button } from './Button'

export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-4 py-12 text-center">
      <div aria-hidden="true" className="mb-1 flex h-12 w-12 items-center justify-center rounded-full bg-slate-100 text-xl">
        ∅
      </div>
      <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
      {description && <p className="max-w-md text-sm text-slate-500">{description}</p>}
      {action && <div className="mt-3">{action}</div>}
    </div>
  )
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center gap-3 rounded-md border border-red-200 bg-red-50 px-4 py-8 text-center">
      <p className="text-sm text-red-800">{message}</p>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  )
}

export function InlineAlert({ tone = 'amber', children }: { tone?: 'amber' | 'red' | 'blue'; children: ReactNode }) {
  const styles = {
    amber: 'border-amber-200 bg-amber-50 text-amber-900',
    red: 'border-red-200 bg-red-50 text-red-900',
    blue: 'border-sky-200 bg-sky-50 text-sky-900',
  }
  return <div className={`rounded-md border px-4 py-3 text-sm ${styles[tone]}`}>{children}</div>
}
