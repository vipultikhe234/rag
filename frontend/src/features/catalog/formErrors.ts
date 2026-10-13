import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { normalizeError } from '../../api/errors'

interface ServerErrorMapping<T extends FieldValues> {
  /** Backend field name (first path segment) to form field. */
  fields: Record<string, Path<T>>
  /** Conflict error codes that belong to one field, e.g. SLUG_ALREADY_EXISTS -> slug. */
  conflicts?: Record<string, Path<T>>
  /** Form field for a violation on "attributes.<slug>", when that slug has a typed input. */
  specField?: (slug: string) => Path<T> | null
  /** Form field for a violation inside a list, e.g. "variants[2].sku"; checked before `fields`. */
  rowField?: (field: string) => Path<T> | null
}

/**
 * Shows backend validation and conflict errors next to the matching form fields.
 * @returns the message for the form-level alert, or null when every error was attached to a field
 */
export function applyServerErrors<T extends FieldValues>(
  err: unknown,
  setError: UseFormSetError<T>,
  mapping: ServerErrorMapping<T>,
): string | null {
  const normalized = normalizeError(err)
  const conflictField = mapping.conflicts?.[normalized.code]
  if (conflictField) {
    setError(conflictField, { message: normalized.message }, { shouldFocus: true })
    return null
  }
  let mapped = false
  for (const violation of normalized.fieldErrors) {
    const specPath = violation.field.startsWith('attributes.')
      ? mapping.specField?.(violation.field.slice('attributes.'.length))
      : null
    const path = specPath ?? mapping.rowField?.(violation.field) ?? mapping.fields[violation.field.split(/[.[]/)[0]]
    if (path) {
      setError(path, { message: violation.message })
      mapped = true
    }
  }
  return mapped ? 'Please correct the highlighted fields.' : normalized.message
}
