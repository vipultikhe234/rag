import { z } from 'zod'

// Mirrors the backend rules (Slugs.PATTERN, @HttpUrl); the backend remains authoritative.
export const slugField = (max: number) =>
  z
    .string()
    .trim()
    .max(max, `At most ${max} characters`)
    .regex(/^$|^[a-z0-9]+(-[a-z0-9]+)*$/, 'Lower-case letters, digits and single hyphens only')

export function isHttpUrl(value: string): boolean {
  if (/\s/.test(value)) return false
  try {
    const url = new URL(value)
    return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname.length > 0
  } catch {
    return false
  }
}

export const optionalUrlField = z
  .string()
  .trim()
  .max(500, 'At most 500 characters')
  .refine((value) => value === '' || isHttpUrl(value), 'Enter a full http:// or https:// address')

export const statusField = z.enum(['ACTIVE', 'INACTIVE'])

export const nullIfBlank = (value: string): string | null => (value.trim() === '' ? null : value.trim())
