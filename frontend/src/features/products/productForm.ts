import { z } from 'zod'
import type {
  AttributeValue,
  CategoryOption,
  Product,
  ProductPayload,
  ProductStatus,
  VariantDraft,
} from '../../api/types'
import { isHttpUrl, slugField } from '../catalog/validation'
import {
  AMOUNT,
  AMOUNT_MESSAGE,
  BARCODE,
  BARCODE_MESSAGE,
  MAX_OPTION_VALUES,
  MAX_OPTIONS,
  MAX_VARIANTS,
  SKU,
  SKU_MESSAGE,
  combinationKey,
  splitValues,
} from './variants'

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

const STOCK = /^\d+$/
const MAX_STOCK = 1_000_000_000

export const imageRowSchema = z.object({
  id: z.string(),
  url: z
    .string()
    .trim()
    .min(1, 'Image URL is required')
    .max(500, 'At most 500 characters')
    .refine(isHttpUrl, 'Enter a full http:// or https:// address'),
  altText: z.string().trim().max(200, 'At most 200 characters'),
})
export type ImageRow = z.infer<typeof imageRowSchema>

export const optionRowSchema = z
  .object({
    id: z.string(),
    name: z.string().trim().min(1, 'Option name is required').max(50, 'At most 50 characters'),
    attributeId: z.string(),
    /** Comma- or newline-separated values */
    values: z.string(),
  })
  .superRefine((row, ctx) => {
    const values = splitValues(row.values)
    const issue = (message: string) => ctx.addIssue({ code: 'custom', path: ['values'], message })
    if (values.length === 0) return issue('Enter at least one value')
    if (values.length > MAX_OPTION_VALUES) return issue(`At most ${MAX_OPTION_VALUES} values`)
    const tooLong = values.find((value) => value.length > 100)
    if (tooLong) issue(`"${tooLong.slice(0, 20)}…" is longer than 100 characters`)
  })
export type OptionRow = z.infer<typeof optionRowSchema>

export const variantRowSchema = z
  .object({
    /** Option name -> value of the generated combination */
    options: z.record(z.string(), z.string()),
    sku: z.string().trim().min(1, 'SKU is required').regex(SKU, SKU_MESSAGE),
    price: z.string().trim().min(1, 'Price is required').regex(AMOUNT, AMOUNT_MESSAGE),
    compareAtPrice: z
      .string()
      .trim()
      .refine((value) => value === '' || AMOUNT.test(value), AMOUNT_MESSAGE),
    stockQuantity: z
      .string()
      .trim()
      .min(1, 'Required')
      .regex(STOCK, 'Whole number of 0 or more')
      .refine((value) => Number(value) <= MAX_STOCK, 'Too large'),
    barcode: z.string().trim().regex(BARCODE, BARCODE_MESSAGE),
    imageUrl: z.string(),
    status: z.enum(['ACTIVE', 'INACTIVE']),
  })
  .superRefine((row, ctx) => {
    if (row.compareAtPrice !== '' && AMOUNT.test(row.compareAtPrice) && AMOUNT.test(row.price)) {
      if (Number(row.compareAtPrice) <= Number(row.price)) {
        ctx.addIssue({ code: 'custom', path: ['compareAtPrice'], message: 'Must be higher than the price' })
      }
    }
  })
export type VariantRow = z.infer<typeof variantRowSchema>

export const productFormSchema = z
  .object({
    name: z.string().trim().min(1, 'Name is required').max(200, 'At most 200 characters'),
    sku: z.string().trim().min(1, 'SKU is required').regex(SKU, SKU_MESSAGE),
    slug: slugField(200),
    description: z.string().trim().min(1, 'Description is required').max(5000, 'At most 5000 characters'),
    /** Top-level category */
    categoryId: z.string().min(1, 'Category is required'),
    /** Optional subcategory of `categoryId`; the product is assigned to the most specific choice. */
    subcategoryId: z.string(),
    brandId: z.string(),
    hasVariants: z.boolean(),
    price: z.string().trim(),
    currency: z
      .string()
      .trim()
      .regex(/^[A-Za-z]{3}$/, '3-letter ISO code such as INR'),
    stockQuantity: z.string().trim(),
    status: z.enum(['ACTIVE', 'INACTIVE']),
    images: z.array(imageRowSchema).max(20, 'At most 20 images'),
    options: z.array(optionRowSchema).max(MAX_OPTIONS, `At most ${MAX_OPTIONS} options`),
    /** New variants to create with this save */
    variants: z.array(variantRowSchema).max(MAX_VARIANTS, `At most ${MAX_VARIANTS} variants`),
    /** Values of the category's attribute definitions, keyed by attribute slug (validated against the definitions). */
    specs: z.record(z.string(), z.string()),
    attributes: attributeRowsSchema,
  })
  .superRefine((values, ctx) => {
    const issue = (path: Array<string | number>, message: string) => ctx.addIssue({ code: 'custom', path, message })
    if (!values.hasVariants) {
      if (values.price === '') issue(['price'], 'Price is required')
      else if (!AMOUNT.test(values.price)) issue(['price'], AMOUNT_MESSAGE)
      if (values.stockQuantity === '') issue(['stockQuantity'], 'Stock quantity is required')
      else if (!STOCK.test(values.stockQuantity)) issue(['stockQuantity'], 'Enter a whole number of 0 or more')
      else if (Number(values.stockQuantity) > MAX_STOCK) issue(['stockQuantity'], 'Stock quantity is too large')
    } else if (values.options.length === 0) {
      issue(['options'], 'Add at least one option, such as Color or Size')
    }

    const urls = new Set<string>()
    values.images.forEach((image, index) => {
      const key = image.url.trim().toLowerCase()
      if (key && urls.has(key)) issue(['images', index, 'url'], 'This image is listed twice')
      urls.add(key)
    })

    const names = new Set<string>()
    values.options.forEach((option, index) => {
      const key = option.name.trim().toLowerCase()
      if (key && names.has(key)) issue(['options', index, 'name'], 'Option names must be unique')
      names.add(key)
    })

    const skus = new Set<string>([values.sku.trim().toUpperCase()])
    const barcodes = new Set<string>()
    const combos = new Set<string>()
    values.variants.forEach((variant, index) => {
      const sku = variant.sku.trim().toUpperCase()
      if (sku && skus.has(sku)) issue(['variants', index, 'sku'], 'SKU is already used in this form')
      skus.add(sku)
      const barcode = variant.barcode.trim()
      if (barcode && barcodes.has(barcode)) issue(['variants', index, 'barcode'], 'Barcode is used twice')
      if (barcode) barcodes.add(barcode)
      const combo = combinationKey(variant.options)
      if (combos.has(combo)) issue(['variants', index, 'sku'], 'This combination is listed twice')
      combos.add(combo)
    })
  })

export type ProductFormValues = z.infer<typeof productFormSchema>

export const emptyProductForm: ProductFormValues = {
  name: '',
  sku: '',
  slug: '',
  description: '',
  categoryId: '',
  subcategoryId: '',
  brandId: '',
  hasVariants: false,
  price: '',
  currency: 'INR',
  stockQuantity: '0',
  status: 'ACTIVE',
  images: [],
  options: [],
  variants: [],
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

/** The category the product is assigned to: the subcategory when one is chosen. */
export function effectiveCategoryId(values: Pick<ProductFormValues, 'categoryId' | 'subcategoryId'>): string {
  return values.subcategoryId || values.categoryId
}

export function toVariantDraft(row: VariantRow): VariantDraft {
  return {
    sku: row.sku.trim(),
    options: row.options,
    price: Number(row.price),
    compareAtPrice: row.compareAtPrice === '' ? null : Number(row.compareAtPrice),
    stockQuantity: Number(row.stockQuantity),
    barcode: row.barcode.trim() || null,
    imageUrl: row.imageUrl || null,
    status: row.status,
  }
}

export function toPayload(
  values: ProductFormValues,
  specAttributes: Record<string, AttributeValue>,
  version?: number,
): ProductPayload {
  return {
    name: values.name.trim(),
    sku: values.sku.trim(),
    slug: values.slug.trim() || null,
    description: values.description.trim(),
    categoryId: effectiveCategoryId(values),
    brandId: values.brandId || null,
    hasVariants: values.hasVariants,
    // Variant products take their price and stock from the variants.
    price: values.hasVariants ? null : Number(values.price),
    currency: values.currency.trim().toUpperCase(),
    stockQuantity: values.hasVariants ? null : Number(values.stockQuantity),
    status: values.status as ProductStatus,
    attributes: { ...rowsToAttributes(values.attributes), ...specAttributes },
    images: values.images.map((image) => ({
      id: image.id || undefined,
      url: image.url.trim(),
      altText: image.altText.trim() || null,
    })),
    options: values.hasVariants
      ? values.options.map((option) => ({
          id: option.id || undefined,
          name: option.name.trim(),
          attributeId: option.attributeId || null,
          values: splitValues(option.values).map((value) => ({ value })),
        }))
      : undefined,
    variants: values.hasVariants && values.variants.length > 0 ? values.variants.map(toVariantDraft) : undefined,
    version,
  }
}

/** Splits a stored category into top-level category + subcategory, using the category tree. */
export function splitCategory(
  categoryId: string | null,
  categories: CategoryOption[],
): Pick<ProductFormValues, 'categoryId' | 'subcategoryId'> {
  if (!categoryId) return { categoryId: '', subcategoryId: '' }
  const byId = new Map(categories.map((category) => [category.id, category]))
  let root = byId.get(categoryId)
  const seen = new Set<string>()
  while (root?.parentId && byId.has(root.parentId) && !seen.has(root.id)) {
    seen.add(root.id)
    root = byId.get(root.parentId)
  }
  if (!root || root.id === categoryId) return { categoryId, subcategoryId: '' }
  return { categoryId: root.id, subcategoryId: categoryId }
}

/** All stored attributes start as free-form rows; rows matching the category's definitions move to `specs`. */
export function fromProduct(product: Product, categories: CategoryOption[]): ProductFormValues {
  return {
    name: product.name,
    sku: product.sku,
    slug: product.slug ?? '',
    description: product.description,
    ...splitCategory(product.categoryId, categories),
    brandId: product.brandId ?? '',
    hasVariants: product.hasVariants,
    price: String(product.price),
    currency: product.currency,
    stockQuantity: String(product.stockQuantity),
    status: product.status,
    images: product.images.map((image) => ({ id: image.id, url: image.url, altText: image.altText ?? '' })),
    options: product.options.map((option) => ({
      id: option.id,
      name: option.name,
      attributeId: option.attributeId ?? '',
      values: option.values.map((value) => value.value).join(', '),
    })),
    variants: [],
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
  slug: 'slug',
  description: 'description',
  category: 'categoryId',
  categoryId: 'categoryId',
  brand: 'brandId',
  brandId: 'brandId',
  hasVariants: 'hasVariants',
  price: 'price',
  currency: 'currency',
  stockQuantity: 'stockQuantity',
  status: 'status',
  images: 'images',
  options: 'options',
  variants: 'variants',
  attributes: 'attributes',
}

const ROW_FIELDS: Record<string, ReadonlySet<string>> = {
  images: new Set(['url', 'altText']),
  options: new Set(['name', 'values', 'attributeId']),
  variants: new Set(['sku', 'price', 'compareAtPrice', 'stockQuantity', 'barcode', 'imageUrl', 'status']),
}

/**
 * Form path for a backend violation inside a list, e.g. "variants[2].compareAtPrice" -> "variants.2.compareAtPrice".
 * Option errors of a variant ("variants[2].options") are shown on its SKU cell; "options[0].values[1].value" on the
 * option's values.
 */
export function rowFieldPath(field: string): string | null {
  const match = /^(images|options|variants)\[(\d+)]\.?([A-Za-z]+)?/.exec(field)
  if (!match) return null
  const [, list, index, name] = match
  if (list === 'variants' && name === 'options') return `variants.${index}.sku`
  if (name && ROW_FIELDS[list].has(name)) return `${list}.${index}.${name}`
  return list === 'options' ? `options.${index}.values` : list === 'images' ? `images.${index}.url` : `variants.${index}.sku`
}
