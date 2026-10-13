import { useId, type ComponentProps, type ReactNode } from 'react'

const CONTROL =
  'block w-full rounded-md border bg-white px-3 py-2 text-sm text-slate-900 shadow-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:bg-slate-100 disabled:text-slate-500'

function controlClass(error: string | undefined, extra = ''): string {
  return `${CONTROL} ${error ? 'border-red-400 focus:ring-red-500' : 'border-slate-300'} ${extra}`
}

interface FieldWrapperProps {
  id: string
  label: string
  error?: string
  hint?: ReactNode
  required?: boolean
  children: ReactNode
}

function FieldWrapper({ id, label, error, hint, required, children }: FieldWrapperProps) {
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">
        {label}
        {required && (
          <span className="ml-0.5 text-red-600" aria-hidden="true">
            *
          </span>
        )}
      </label>
      {children}
      {hint && !error && (
        <p id={`${id}-hint`} className="text-xs text-slate-500">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className="text-xs text-red-600" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

interface CommonProps {
  label: string
  error?: string
  hint?: ReactNode
}

function describedBy(id: string, error?: string, hint?: ReactNode): string | undefined {
  if (error) return `${id}-error`
  if (hint) return `${id}-hint`
  return undefined
}

export function TextField({ label, error, hint, id, className, required, ...rest }: CommonProps & ComponentProps<'input'>) {
  const generated = useId()
  const fieldId = id ?? generated
  return (
    <FieldWrapper id={fieldId} label={label} error={error} hint={hint} required={required}>
      <input
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-required={required || undefined}
        aria-describedby={describedBy(fieldId, error, hint)}
        className={controlClass(error, className)}
        {...rest}
      />
    </FieldWrapper>
  )
}

export function TextAreaField({
  label,
  error,
  hint,
  id,
  className,
  required,
  ...rest
}: CommonProps & ComponentProps<'textarea'>) {
  const generated = useId()
  const fieldId = id ?? generated
  return (
    <FieldWrapper id={fieldId} label={label} error={error} hint={hint} required={required}>
      <textarea
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-required={required || undefined}
        aria-describedby={describedBy(fieldId, error, hint)}
        className={controlClass(error, className)}
        {...rest}
      />
    </FieldWrapper>
  )
}

export function SelectField({
  label,
  error,
  hint,
  id,
  className,
  required,
  children,
  ...rest
}: CommonProps & ComponentProps<'select'>) {
  const generated = useId()
  const fieldId = id ?? generated
  return (
    <FieldWrapper id={fieldId} label={label} error={error} hint={hint} required={required}>
      <select
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(fieldId, error, hint)}
        className={controlClass(error, className)}
        {...rest}
      >
        {children}
      </select>
    </FieldWrapper>
  )
}
