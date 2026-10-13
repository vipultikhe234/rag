import { z } from 'zod'
import type { AttributeValue, Product, ProductPayload, ProductStatus } from '../../api/types'

// Mirrors the backend Bean Validation rules so users get immediate feedback; the backend remains authoritative.
const ATTRIBUTE_KEY = /^[\p{L}\p{N}][\p{L}\p{N} _.()/-]*$/u
export const MAX_ATTRIBUTES = 50

export const attributeTypes = ['text', 'number', 'boolean', 'list'] as const
export type AttributeType = (typeof attributeTypes)[number]

export const attributeRowSchema = z
  .object({
    key: z
      .string()
      .trim()
      .min(1, 'Name is required')
      .max(64, 'At most 64 characters')
      .regex(ATTRIBUTE_KEY, 'Letters, digits, spaces or _ . ( ) / - only'),
    type: z.enum(attributeTypes),
    value: z.string().trim().min(1, 'Value is required').max(500, 'At most 500 characters'),
  })
  .superRefine((row, ctx) => {
    if (row.type === 'number' && !Number.isFinite(Number(row.value))) {
      ctx.addIssue({ code: 'custom', path: ['value'], message: 'Enter a valid number' })
    }
    if (row.type === 'boolean' && row.value !== 'true' && row.value !== 'false') {
      ctx.addIssue({ code: 'custom', path: ['value'], message: 'Choose yes or no' })
    }
    if (row.type === 'list') {
      const items = splitList(row.value)
      if (items.length === 0) {
        ctx.addIssue({ code: 'custom', path: ['value'], message: 'Enter at least one value' })
      } else if (items.length > 20) {
        ctx.addIssue({ code: 'custom', path: ['value'], message: 'At most 20 values' })
      }
    }
  })

export type AttributeRow = z.infer<typeof attributeRowSchema>

/** Free-form rows with unique keys; shared by the product and variant forms. */
export const attributeRowsSchema = z
  .array(attributeRowSchema)
  .max(MAX_ATTRIBUTES, `At most ${MAX_ATTRIBUTES} attributes`)
  .superRefine((rows, ctx) => {
    const seen = new Set<string>()
    rows.forEach((row, index) => {
      const key = row.key.trim()
      if (seen.has(key)) {
        ctx.addIssue({ code: 'custom', path: [index, 'key'], message: 'Duplicate attribute name' })
      }
      seen.add(key)
    })
  })

export const productFormSchema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(200, 'At most 200 characters'),
  sku: z
    .string()
    .trim()
    .min(1, 'SKU is required')
    .regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, "1-64 letters, digits, '.', '_' or '-', starting with a letter or digit"),
  description: z.string().trim().min(1, 'Description is required').max(5000, 'At most 5000 characters'),
  categoryId: z.string().min(1, 'Category is required'),
  brandId: z.string(),
  price: z
    .string()
    .trim()
    .min(1, 'Price is required')
    .regex(/^\d{1,10}(\.\d{1,2})?$/, 'Enter a non-negative amount with at most 2 decimals'),
  currency: z
    .string()
    .trim()
    .regex(/^[A-Za-z]{3}$/, '3-letter ISO code such as INR'),
  stockQuantity: z
    .string()
    .trim()
    .min(1, 'Stock quantity is required')
    .regex(/^\d+$/, 'Enter a whole number of 0 or more')
    .refine((value) => Number(value) <= 1_000_000_000, 'Stock quantity is too large'),
  status: z.enum(['ACTIVE', 'INACTIVE']),
  /** Values of the category's attribute definitions, keyed by attribute slug (validated against the definitions). */
  specs: z.record(z.string(), z.string()),
  attributes: attributeRowsSchema,
})

export type ProductFormValues = z.infer<typeof productFormSchema>

export const emptyProductForm: ProductFormValues = {
  name: '',
  sku: '',
  description: '',
  categoryId: '',
  brandId: '',
  price: '',
  currency: 'INR',
  stockQuantity: '0',
  status: 'ACTIVE',
  specs: {},
  attributes: [],
}

function splitList(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function toAttributeValue(row: AttributeRow): AttributeValue {
  switch (row.type) {
    case 'number':
      return Number(row.value)
    case 'boolean':
      return row.value === 'true'
    case 'list':
      return splitList(row.value)
    default:
      return row.value.trim()
  }
}

export function rowsToAttributes(rows: AttributeRow[]): Record<string, AttributeValue> {
  const attributes: Record<string, AttributeValue> = {}
  for (const row of rows) {
    attributes[row.key.trim()] = toAttributeValue(row)
  }
  return attributes
}

export function toPayload(
  values: ProductFormValues,
  specAttributes: Record<string, AttributeValue>,
  version?: number,
): ProductPayload {
  return {
    name: values.name.trim(),
    sku: values.sku.trim(),
    description: values.description.trim(),
    categoryId: values.categoryId,
    brandId: values.brandId || null,
    price: Number(values.price),
    currency: values.currency.trim().toUpperCase(),
    stockQuantity: Number(values.stockQuantity),
    status: values.status as ProductStatus,
    attributes: { ...rowsToAttributes(values.attributes), ...specAttributes },
    version,
  }
}

/** All stored attributes start as free-form rows; rows matching the category's definitions move to `specs`. */
export function fromProduct(product: Product): ProductFormValues {
  return {
    name: product.name,
    sku: product.sku,
    description: product.description,
    categoryId: product.categoryId ?? '',
    brandId: product.brandId ?? '',
    price: String(product.price),
    currency: product.currency,
    stockQuantity: String(product.stockQuantity),
    status: product.status,
    specs: {},
    attributes: Object.entries(product.attributes ?? {}).map(([key, value]) => toRow(key, value)),
  }
}

export function toRow(key: string, value: AttributeValue): AttributeRow {
  if (Array.isArray(value)) return { key, type: 'list', value: value.join(', ') }
  if (typeof value === 'number') return { key, type: 'number', value: String(value) }
  if (typeof value === 'boolean') return { key, type: 'boolean', value: String(value) }
  return { key, type: 'text', value: String(value) }
}

/** Maps backend field names to form fields for server-side validation errors. */
export const productFieldMap: Record<string, keyof ProductFormValues> = {
  name: 'name',
  sku: 'sku',
  description: 'description',
  category: 'categoryId',
  categoryId: 'categoryId',
  brand: 'brandId',
  brandId: 'brandId',
  price: 'price',
  currency: 'currency',
  stockQuantity: 'stockQuantity',
  status: 'status',
  attributes: 'attributes',
}
