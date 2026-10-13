import { useMemo, useState } from 'react'
import { useFieldArray, useWatch, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form'
import { Link } from 'react-router'
import type { ProductAttribute, ProductVariant } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/FormField'
import { InlineAlert } from '../../components/ui/States'
import { formatPrice } from '../../lib/format'
import { StockBadge } from '../catalog/CatalogUi'
import { linkClass } from '../catalog/styles'
import { SalePrice } from './Price'
import type { ProductFormValues } from './productForm'
import {
  AMOUNT,
  MAX_VARIANTS,
  combinationCount,
  combinationKey,
  combinationTitle,
  combinations,
  isStale,
  optionDefinitions,
  suggestSku,
} from './variants'

const CELL_INPUT = 'w-full min-w-0 rounded border px-2 py-1 text-sm'

interface VariantBuilderProps {
  control: Control<ProductFormValues>
  register: UseFormRegister<ProductFormValues>
  errors: FieldErrors<ProductFormValues>
  attributes: ProductAttribute[]
  existing: ProductVariant[]
  currency: string
}

function cellClass(error: string | undefined): string {
  return `${CELL_INPUT} ${error ? 'border-red-400' : 'border-slate-300'}`
}

export function VariantBuilder({ control, register, errors, attributes, existing, currency }: VariantBuilderProps) {
  const { fields, append, remove, replace } = useFieldArray({ control, name: 'variants' })
  const optionRows = useWatch({ control, name: 'options' })
  const rows = useWatch({ control, name: 'variants' })
  const productSku = useWatch({ control, name: 'sku' })
  const images = useWatch({ control, name: 'images' })
  const [defaultPrice, setDefaultPrice] = useState('')
  const [defaultStock, setDefaultStock] = useState('0')
  const [notice, setNotice] = useState<string | null>(null)

  const definitions = useMemo(() => optionDefinitions(optionRows, attributes), [optionRows, attributes])
  const possible = combinationCount(definitions)
  const existingKeys = useMemo(
    () => new Set(existing.map((v) => combinationKey(Object.fromEntries(v.options.map((o) => [o.name, o.value]))))),
    [existing],
  )
  const imageUrls = (images ?? []).map((image) => image.url.trim()).filter((url) => url !== '')

  const generate = () => {
    setNotice(null)
    const inRows = new Set((rows ?? []).map((row) => combinationKey(row.options)))
    const missing = combinations(definitions).filter((combo) => {
      const key = combinationKey(combo)
      return !existingKeys.has(key) && !inRows.has(key)
    })
    if (missing.length === 0) {
      setNotice('Every combination already has a variant or a row below.')
      return
    }
    const room = MAX_VARIANTS - existing.length - (rows?.length ?? 0)
    if (missing.length > room) {
      setNotice(
        `${missing.length} new combinations would exceed the limit of ${MAX_VARIANTS} variants per product. Remove some option values first.`,
      )
      return
    }
    append(
      missing.map((combo) => ({
        options: Object.fromEntries(combo.map(({ name, value }) => [name, value])),
        sku: suggestSku(productSku ?? '', combo),
        price: AMOUNT.test(defaultPrice.trim()) ? defaultPrice.trim() : '',
        compareAtPrice: '',
        stockQuantity: /^\d+$/.test(defaultStock.trim()) ? defaultStock.trim() : '0',
        barcode: '',
        imageUrl: '',
        status: 'ACTIVE' as const,
      })),
    )
  }

  const removeStale = () => replace((rows ?? []).filter((row) => !isStale(row.options, definitions)))
  const staleCount = (rows ?? []).filter((row) => isStale(row.options, definitions)).length
  const variantsError = errors.variants?.message ?? errors.variants?.root?.message

  return (
    <div className="space-y-4">
      {existing.length > 0 && (
        <div className="space-y-2">
          <p className="text-sm font-medium text-slate-700">Existing variants ({existing.length})</p>
          <div className="overflow-x-auto rounded-md border border-slate-200">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <tr>
                  <th className="px-3 py-2">Variant</th>
                  <th className="px-3 py-2">SKU</th>
                  <th className="px-3 py-2 text-right">Price</th>
                  <th className="px-3 py-2 text-right">Stock</th>
                  <th className="px-3 py-2">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {existing.map((variant) => (
                  <tr key={variant.id}>
                    <td className="px-3 py-2">
                      <Link to={`/product-variants/${variant.id}/edit`} className={linkClass.text}>
                        {variant.variantName}
                      </Link>
                    </td>
                    <td className="px-3 py-2 font-mono text-xs">{variant.sku}</td>
                    <td className="px-3 py-2 text-right">
                      <SalePrice price={variant.price} compareAtPrice={variant.compareAtPrice} currency={currency} />
                    </td>
                    <td className="px-3 py-2 text-right">
                      <span className="mr-2 tabular-nums">{variant.stockQuantity}</span>
                      <StockBadge level={variant.stockLevel} />
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={variant.status === 'ACTIVE' ? 'green' : 'gray'}>
                        {variant.status === 'ACTIVE' ? 'Active' : 'Inactive'}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="text-xs text-slate-500">
            Existing variants are edited, deactivated or reactivated individually; variants are never deleted.
          </p>
        </div>
      )}

      <div className="grid gap-3 rounded-md bg-slate-50 p-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
        <TextField
          label={`Default price (${currency || 'INR'})`}
          inputMode="decimal"
          value={defaultPrice}
          placeholder="Optional"
          error={defaultPrice.trim() && !AMOUNT.test(defaultPrice.trim()) ? 'Invalid amount' : undefined}
          onChange={(event) => setDefaultPrice(event.target.value)}
        />
        <TextField
          label="Default stock"
          inputMode="numeric"
          value={defaultStock}
          onChange={(event) => setDefaultStock(event.target.value)}
        />
        <Button variant="secondary" onClick={generate} disabled={possible === 0}>
          Generate combinations
        </Button>
        <p className="text-xs text-slate-500 sm:col-span-3">
          {possible === 0
            ? 'Add options with values to generate combinations.'
            : `${possible} possible combination${possible === 1 ? '' : 's'}; ${existing.length} already exist. New rows use the defaults above and can be edited before saving.`}
        </p>
      </div>

      {notice && <InlineAlert tone="amber">{notice}</InlineAlert>}
      {staleCount > 0 && (
        <InlineAlert tone="amber">
          {staleCount} row{staleCount === 1 ? ' no longer matches' : 's no longer match'} the options above.{' '}
          <button type="button" className="font-medium underline" onClick={removeStale}>
            Remove {staleCount === 1 ? 'it' : 'them'}
          </button>{' '}
          and generate again.
        </InlineAlert>
      )}
      {variantsError && (
        <p className="text-xs text-red-600" role="alert">
          {variantsError}
        </p>
      )}

      {fields.length > 0 && (
        <div className="overflow-x-auto rounded-md border border-slate-200">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <caption className="sr-only">New variants (preview)</caption>
            <thead className="bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
              <tr>
                <th className="px-2 py-2">Variant</th>
                <th className="px-2 py-2">SKU *</th>
                <th className="px-2 py-2">Price *</th>
                <th className="px-2 py-2">Compare-at</th>
                <th className="px-2 py-2">Stock *</th>
                <th className="px-2 py-2">Barcode</th>
                {imageUrls.length > 0 && <th className="px-2 py-2">Image</th>}
                <th className="px-2 py-2">Status</th>
                <th className="px-2 py-2">
                  <span className="sr-only">Remove</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {fields.map((field, index) => {
                const row = rows?.[index]
                const rowErrors = errors.variants?.[index]
                const combo = Object.entries(row?.options ?? field.options).map(([name, value]) => ({ name, value }))
                const stale = row ? isStale(row.options, definitions) : false
                const title = combinationTitle(combo, definitions)
                const error = (message: string | undefined) =>
                  message ? <p className="mt-0.5 text-xs text-red-600">{message}</p> : null
                return (
                  <tr key={field.id} className={stale ? 'bg-amber-50' : undefined}>
                    <td className="whitespace-nowrap px-2 py-2 font-medium text-slate-800">
                      {title}
                      {stale && (
                        <span className="ml-1">
                          <Badge tone="amber">Outdated</Badge>
                        </span>
                      )}
                    </td>
                    <td className="px-2 py-2">
                      <input
                        aria-label={`SKU for ${title}`}
                        className={`${cellClass(rowErrors?.sku?.message)} font-mono`}
                        {...register(`variants.${index}.sku`)}
                      />
                      {error(rowErrors?.sku?.message)}
                    </td>
                    <td className="px-2 py-2">
                      <input
                        aria-label={`Price for ${title}`}
                        inputMode="decimal"
                        className={cellClass(rowErrors?.price?.message)}
                        {...register(`variants.${index}.price`)}
                      />
                      {error(rowErrors?.price?.message)}
                    </td>
                    <td className="px-2 py-2">
                      <input
                        aria-label={`Compare-at price for ${title}`}
                        inputMode="decimal"
                        className={cellClass(rowErrors?.compareAtPrice?.message)}
                        {...register(`variants.${index}.compareAtPrice`)}
                      />
                      {error(rowErrors?.compareAtPrice?.message)}
                    </td>
                    <td className="px-2 py-2">
                      <input
                        aria-label={`Stock for ${title}`}
                        inputMode="numeric"
                        className={`${cellClass(rowErrors?.stockQuantity?.message)} w-20`}
                        {...register(`variants.${index}.stockQuantity`)}
                      />
                      {error(rowErrors?.stockQuantity?.message)}
                    </td>
                    <td className="px-2 py-2">
                      <input
                        aria-label={`Barcode for ${title}`}
                        className={`${cellClass(rowErrors?.barcode?.message)} font-mono`}
                        {...register(`variants.${index}.barcode`)}
                      />
                      {error(rowErrors?.barcode?.message)}
                    </td>
                    {imageUrls.length > 0 && (
                      <td className="px-2 py-2">
                        <select
                          aria-label={`Image for ${title}`}
                          className={cellClass(rowErrors?.imageUrl?.message)}
                          {...register(`variants.${index}.imageUrl`)}
                        >
                          <option value="">None</option>
                          {imageUrls.map((url, imageIndex) => (
                            <option key={url} value={url}>
                              Image {imageIndex + 1}
                            </option>
                          ))}
                        </select>
                        {error(rowErrors?.imageUrl?.message)}
                      </td>
                    )}
                    <td className="px-2 py-2">
                      <select
                        aria-label={`Status for ${title}`}
                        className={cellClass(undefined)}
                        {...register(`variants.${index}.status`)}
                      >
                        <option value="ACTIVE">Active</option>
                        <option value="INACTIVE">Inactive</option>
                      </select>
                    </td>
                    <td className="px-2 py-2 text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-red-600 hover:bg-red-50"
                        onClick={() => remove(index)}
                        aria-label={`Remove ${title}`}
                      >
                        Remove
                      </Button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      {fields.length > 0 && (
        <p className="text-xs text-slate-500">
          {fields.length} new variant{fields.length === 1 ? '' : 's'} will be created when you save
          {(() => {
            const prices = (rows ?? []).map((row) => row.price.trim()).filter((p) => AMOUNT.test(p)).map(Number)
            return prices.length > 0
              ? `, priced ${formatPrice(Math.min(...prices), currency)} – ${formatPrice(Math.max(...prices), currency)}.`
              : '.'
          })()}
        </p>
      )}
    </div>
  )
}
