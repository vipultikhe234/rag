interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg'
  label?: string
}

const SIZES = { sm: 'h-4 w-4 border-2', md: 'h-6 w-6 border-2', lg: 'h-10 w-10 border-[3px]' }

export function Spinner({ size = 'md', label }: SpinnerProps) {
  return (
    <span role={label ? 'status' : undefined} className="inline-flex items-center gap-2">
      <span
        aria-hidden="true"
        className={`inline-block animate-spin rounded-full border-current border-r-transparent ${SIZES[size]}`}
      />
      {label && <span className="sr-only">{label}</span>}
    </span>
  )
}

export function FullPageSpinner({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex min-h-[50vh] items-center justify-center text-indigo-600">
      <Spinner size="lg" label={label} />
    </div>
  )
}

export function LoadingBlock({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-3 py-12 text-sm text-slate-500" role="status">
      <Spinner size="md" />
      <span>{label}…</span>
    </div>
  )
}
