import type { AttributeValue, ProductAttribute } from '../../api/types'
import { toRow, type AttributeRow } from '../products/productForm'

/** Active definitions, plus inactive ones that still hold a stored value (shown read-only). */
export function visibleDefinitions(
  definitions: ProductAttribute[],
  original: Record<string, AttributeValue>,
): ProductAttribute[] {
  return definitions.filter((d) => d.status === 'ACTIVE' || original[d.slug] !== undefined)
}

export function toSpecString(value: AttributeValue | undefined): string {
  if (value === undefined || value === null) return ''
  if (Array.isArray(value)) return value.join(', ')
  return String(value)
}

function parseSpec(definition: ProductAttribute, raw: string): { value?: AttributeValue; error?: string } {
  const text = raw.trim()
  if (text === '') return {}
  switch (definition.dataType) {
    case 'NUMBER': {
      const number = Number(text)
      return Number.isFinite(number) ? { value: number } : { error: `${definition.name} must be a number` }
    }
    case 'BOOLEAN':
      return text === 'true' || text === 'false' ? { value: text === 'true' } : { error: 'Choose yes or no' }
    case 'SELECT': {
      const option = definition.options.find((o) => o.toLowerCase() === text.toLowerCase())
      return option ? { value: option } : { error: `Choose one of: ${definition.options.join(', ')}` }
    }
    default:
      return text.length > 500 ? { error: 'At most 500 characters' } : { value: text }
  }
}

/**
 * Converts the typed specification inputs into attribute values. Inactive attributes keep their stored value
 * unchanged (the backend rejects changes to them).
 */
export function buildSpecAttributes(
  definitions: ProductAttribute[],
  specs: Record<string, string>,
  original: Record<string, AttributeValue>,
  enforceRequired: boolean,
): { attributes: Record<string, AttributeValue>; errors: Record<string, string> } {
  const attributes: Record<string, AttributeValue> = {}
  const errors: Record<string, string> = {}
  for (const definition of definitions) {
    if (definition.status !== 'ACTIVE') {
      if (original[definition.slug] !== undefined) attributes[definition.slug] = original[definition.slug]
      continue
    }
    const { value, error } = parseSpec(definition, specs[definition.slug] ?? '')
    if (error) errors[definition.slug] = error
    else if (value !== undefined) attributes[definition.slug] = value
    else if (enforceRequired && definition.required) errors[definition.slug] = `${definition.name} is required`
  }
  return { attributes, errors }
}

/**
 * Client-side check of the typed inputs plus free-form rows that would shadow a typed input.
 * @returns the attribute values for the payload and `[form path, message]` pairs for invalid fields
 */
export function collectSpecs(
  definitions: ProductAttribute[],
  specs: Record<string, string>,
  rows: AttributeRow[],
  original: Record<string, AttributeValue>,
  enforceRequired: boolean,
): { attributes: Record<string, AttributeValue>; fieldErrors: Array<[string, string]> } {
  const { attributes, errors } = buildSpecAttributes(definitions, specs, original, enforceRequired)
  const fieldErrors: Array<[string, string]> = Object.entries(errors).map(([slug, message]) => [`specs.${slug}`, message])
  const slugs = new Set(definitions.map((d) => d.slug))
  rows.forEach((row, index) => {
    if (slugs.has(row.key.trim())) {
      fieldErrors.push([`attributes.${index}.key`, 'This attribute has its own field above'])
    }
  })
  return { attributes, fieldErrors }
}

/**
 * Moves values between the typed specification inputs and the free-form rows when the applicable definitions
 * change (category or product switched), so no entered value is silently dropped.
 */
export function redistribute(
  definitions: ProductAttribute[],
  specs: Record<string, string>,
  rows: AttributeRow[],
): { specs: Record<string, string>; rows: AttributeRow[] } {
  const slugs = new Set(definitions.map((d) => d.slug))
  const nextSpecs: Record<string, string> = {}
  const nextRows: AttributeRow[] = []
  for (const row of rows) {
    const key = row.key.trim()
    if (slugs.has(key) && !(key in nextSpecs)) nextSpecs[key] = row.value
    else nextRows.push(row)
  }
  for (const [key, value] of Object.entries(specs)) {
    if (slugs.has(key)) {
      if (!(key in nextSpecs)) nextSpecs[key] = value
    } else if (value.trim() !== '') {
      const numeric = value.trim() !== '' && Number.isFinite(Number(value))
      nextRows.push(numeric ? toRow(key, Number(value)) : toRow(key, value))
    }
  }
  return { specs: nextSpecs, rows: nextRows }
}

export function formatAttributeValue(value: AttributeValue): string {
  if (Array.isArray(value)) return value.join(', ')
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  return String(value)
}
