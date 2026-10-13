import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useState } from 'react'
import { useForm, useWatch, type Path } from 'react-hook-form'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { z } from 'zod'
import { productApi, variantApi } from '../../api/endpoints'
import type { AttributeValue, Product, ProductVariant } from '../../api/types'
import { useToast } from '../../components/toast/toastContext'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { SelectField, TextField } from '../../components/ui/FormField'
import { LoadingBlock } from '../../components/ui/Spinner'
import { ErrorState, InlineAlert } from '../../components/ui/States'
import { PageHeader } from '../../features/catalog/CatalogUi'
import { collectSpecs } from '../../features/catalog/attributeValues'
import { applyServerErrors } from '../../features/catalog/formErrors'
import { useLoader } from '../../features/catalog/hooks'
import { SpecFields } from '../../features/catalog/SpecFields'
import { linkClass } from '../../features/catalog/styles'
import { useSpecDefinitions } from '../../features/catalog/useSpecDefinitions'
import { statusField } from '../../features/catalog/validation'
import { AttributesEditor } from '../../features/products/AttributesEditor'
import { attributeRowsSchema, rowsToAttributes, toRow } from '../../features/products/productForm'
import { AMOUNT, AMOUNT_MESSAGE, BARCODE, BARCODE_MESSAGE, SKU, SKU_MESSAGE, optionLabel } from '../../features/products/variants'

const NO_ATTRIBUTES: Record<string, AttributeValue> = {}

const schema = z
  .object({
    productId: z.string().min(1, 'Product is required'),
    sku: z.string().trim().min(1, 'SKU is required').regex(SKU, SKU_MESSAGE),
    /** Option id -> chosen value text */
    optionValues: z.record(z.string(), z.string()),
    price: z.string().trim().min(1, 'Price is required').regex(AMOUNT, AMOUNT_MESSAGE),
    compareAtPrice: z
      .string()
      .trim()
      .refine((value) => value === '' || AMOUNT.test(value), AMOUNT_MESSAGE),
    stockQuantity: z
      .string()
      .trim()
      .min(1, 'Stock quantity is required')
      .regex(/^\d+$/, 'Enter a whole number of 0 or more')
      .refine((value) => Number(value) <= 1_000_000_000, 'Stock quantity is too large'),
    barcode: z.string().trim().regex(BARCODE, BARCODE_MESSAGE),
    imageId: z.string(),
    status: statusField,
    specs: z.record(z.string(), z.string()),
    attributes: attributeRowsSchema,
  })
  .superRefine((values, ctx) => {
    if (values.compareAtPrice !== '' && AMOUNT.test(values.compareAtPrice) && AMOUNT.test(values.price)) {
      if (Number(values.compareAtPrice) <= Number(values.price)) {
        ctx.addIssue({
          code: 'custom',
          path: ['compareAtPrice'],
          message: 'The compare-at price must be higher than the price',
        })
      }
    }
  })
type FormValues = z.infer<typeof schema>

function toForm(variant: ProductVariant): FormValues {
  return {
    productId: variant.productId,
    sku: variant.sku,
    optionValues: Object.fromEntries(variant.options.map((option) => [option.optionId, option.value])),
    price: String(variant.price),
    compareAtPrice: variant.compareAtPrice === null ? '' : String(variant.compareAtPrice),
    stockQuantity: String(variant.stockQuantity),
    barcode: variant.barcode ?? '',
    imageId: variant.imageId ?? '',
    status: variant.status,
    specs: {},
    attributes: Object.entries(variant.attributes ?? {}).map(([key, value]) => toRow(key, value)),
  }
}

const FIELD_MAP: Record<string, Path<FormValues>> = {
  productId: 'productId',
  sku: 'sku',
  options: 'optionValues',
  price: 'price',
  compareAtPrice: 'compareAtPrice',
  stockQuantity: 'stockQuantity',
  barcode: 'barcode',
  imageId: 'imageId',
  status: 'status',
  attributes: 'attributes',
}

function variantTitle(product: Product | null, optionValues: Record<string, string>): string {
  if (!product) return ''
  return product.options
    .map((option) => (optionValues[option.id] ? optionLabel(optionValues[option.id], option.unit) : null))
    .filter((label): label is string => label !== null)
    .join(' / ')
}

export function VariantFormPage({ mode }: { mode: 'create' | 'edit' }) {
  const { id } = useParams()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [serverError, setServerError] = useState<string | null>(null)

  const record = useLoader((signal) => (id ? variantApi.get(id, signal) : Promise.resolve(null)), id ?? 'new')
  const products = useLoader(
    (signal) => productApi.list({ hasVariants: true, page: 0, size: 100, sort: 'name,asc' }, signal),
    'variant-products',
  )
  const variant = record.data

  const {
    register,
    control,
    handleSubmit,
    reset,
    setError,
    getValues,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      productId: searchParams.get('productId') ?? '',
      sku: '',
      optionValues: {},
      price: '',
      compareAtPrice: '',
      stockQuantity: '0',
      barcode: '',
      imageId: '',
      status: 'ACTIVE',
      specs: {},
      attributes: [],
    },
    mode: 'onBlur',
  })
  const productId = useWatch({ control, name: 'productId' })
  const attributeRows = useWatch({ control, name: 'attributes' })
  const optionValues = useWatch({ control, name: 'optionValues' })

  useEffect(() => {
    if (variant) reset(toForm(variant))
  }, [variant, reset])

  // The list holds up to 100 variant products; the product of the variant (or from the URL) is loaded on its own,
  // so its options and images are always complete.
  const single = useLoader(
    (signal) => (productId ? productApi.get(productId, signal) : Promise.resolve(null)),
    productId,
  )
  const selected = single.data?.id === productId ? single.data : null
  const categoryId = selected?.categoryId ?? ''

  const original = variant?.attributes ?? NO_ATTRIBUTES
  const specs = useSpecDefinitions(categoryId, original, getValues, setValue)

  // New variants need an active variant product; a variant's product cannot change.
  const productChoices = useMemo(() => {
    const all = (products.data?.content ?? []).filter((p) => p.status === 'ACTIVE' || p.id === variant?.productId)
    return selected && !all.some((p) => p.id === selected.id) ? [selected, ...all] : all
  }, [products.data, selected, variant])

  const onProductChange = () => {
    setValue('optionValues', {})
    setValue('imageId', '')
  }

  const onSubmit = async (values: FormValues) => {
    setServerError(null)
    if (!specs.definitions || !selected) {
      setServerError("The product's options and attributes have not loaded yet. Wait a moment and try again.")
      return
    }
    if (!selected.hasVariants) {
      setError('productId', { message: 'Choose a product that sells through variants' })
      return
    }
    const missing = selected.options.filter((option) => !values.optionValues[option.id])
    if (missing.length > 0) {
      missing.forEach((option) =>
        setError(`optionValues.${option.id}` as Path<FormValues>, { message: `Choose a ${option.name}` }),
      )
      return
    }
    const { attributes, fieldErrors } = collectSpecs(specs.definitions, values.specs, values.attributes, original, false)
    if (fieldErrors.length > 0) {
      fieldErrors.forEach(([path, message]) => setError(path as Path<FormValues>, { message }))
      setServerError('Please correct the highlighted fields.')
      return
    }
    const payload = {
      productId: values.productId,
      sku: values.sku.trim(),
      options: Object.fromEntries(selected.options.map((option) => [option.name, values.optionValues[option.id]])),
      attributes: { ...rowsToAttributes(values.attributes), ...attributes },
      price: Number(values.price),
      compareAtPrice: values.compareAtPrice === '' ? null : Number(values.compareAtPrice),
      stockQuantity: Number(values.stockQuantity),
      barcode: values.barcode.trim() || null,
      imageId: values.imageId || null,
      status: values.status,
      version: variant?.version,
    }
    try {
      const saved = mode === 'create' ? await variantApi.create(payload) : await variantApi.update(id!, payload)
      toast.success(mode === 'create' ? `Variant ${saved.variantName} created.` : `Variant ${saved.variantName} saved.`)
      navigate(`/product-variants/${saved.id}`)
    } catch (err) {
      const typedSlugs = new Set(specs.definitions.filter((d) => d.status === 'ACTIVE').map((d) => d.slug))
      setServerError(
        applyServerErrors(err, setError, {
          fields: FIELD_MAP,
          conflicts: {
            SKU_ALREADY_EXISTS: 'sku',
            BARCODE_ALREADY_EXISTS: 'barcode',
            VARIANT_COMBINATION_EXISTS: 'optionValues',
          },
          specField: (slug) => (typedSlugs.has(slug) ? (`specs.${slug}` as Path<FormValues>) : null),
        }),
      )
    }
  }

  if ((mode === 'edit' && record.loading) || products.loading) return <LoadingBlock label="Loading variant" />
  if (mode === 'edit' && (record.error || !variant)) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <PageHeader title="Edit variant" back={{ to: '/product-variants', label: 'Back to variants' }} />
        <ErrorState message={record.error ?? 'The variant was not found.'} onRetry={record.reload} />
      </div>
    )
  }

  const back =
    mode === 'edit' && id
      ? { to: `/product-variants/${id}`, label: 'Back to variant' }
      : productId
        ? { to: `/products/${productId}?tab=variants`, label: 'Back to product' }
        : { to: '/product-variants', label: 'Back to variants' }
  const title = variantTitle(selected, optionValues ?? {})
  const combinationError = (errors.optionValues as { message?: string } | undefined)?.message

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <PageHeader title={mode === 'create' ? 'Add variant' : `Edit ${variant?.variantName ?? 'variant'}`} back={back} />
      {serverError && <InlineAlert tone="red">{serverError}</InlineAlert>}
      {products.error && <InlineAlert tone="amber">Products could not be loaded: {products.error}</InlineAlert>}

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-6">
        <Card title="Product and options">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="sm:col-span-2">
              {mode === 'edit' && variant ? (
                <div className="text-sm">
                  <p className="font-medium text-slate-700">Product</p>
                  <Link to={`/products/${variant.productId}`} className={linkClass.text}>
                    {variant.productName} ({variant.productSku})
                  </Link>
                  <p className="text-xs text-slate-500">A variant cannot be moved to another product.</p>
                </div>
              ) : (
                <SelectField
                  label="Product"
                  required
                  hint="Only products that sell through variants are listed."
                  error={errors.productId?.message}
                  {...register('productId', { onChange: onProductChange })}
                >
                  <option value="">Select a product</option>
                  {productChoices.map((product) => (
                    <option key={product.id} value={product.id}>
                      {product.name} ({product.sku}){product.status === 'INACTIVE' ? ' (inactive)' : ''}
                    </option>
                  ))}
                </SelectField>
              )}
            </div>
            {!productId ? null : single.error && !selected ? (
              <div className="sm:col-span-2">
                <ErrorState message={single.error} onRetry={single.reload} />
              </div>
            ) : !selected ? (
              <div className="sm:col-span-2">
                <LoadingBlock label="Loading product" />
              </div>
            ) : !selected.hasVariants ? (
              <div className="sm:col-span-2">
                <InlineAlert tone="amber">
                  {selected.name} is a simple product. Turn on variants in the{' '}
                  <Link to={`/products/${selected.id}/edit`} className={linkClass.text}>
                    product form
                  </Link>{' '}
                  first.
                </InlineAlert>
              </div>
            ) : (
              <>
                {selected.options.map((option) => (
                  <SelectField
                    key={option.id}
                    label={option.unit ? `${option.name} (${option.unit})` : option.name}
                    required
                    error={errors.optionValues?.[option.id]?.message}
                    {...register(`optionValues.${option.id}`)}
                  >
                    <option value="">Choose…</option>
                    {option.values.map((value) => (
                      <option key={value.id} value={value.value}>
                        {optionLabel(value.value, option.unit)}
                      </option>
                    ))}
                  </SelectField>
                ))}
                <div className="sm:col-span-2 text-sm text-slate-600">
                  Variant title: <span className="font-medium text-slate-900">{title || '—'}</span>
                  <span className="block text-xs text-slate-500">
                    Generated from the option values. Add new values in the product form.
                  </span>
                  {combinationError && <p className="mt-1 text-xs text-red-600">{combinationError}</p>}
                </div>
              </>
            )}
            <TextField
              label="SKU"
              required
              hint="Must be unique across products and variants."
              className="font-mono"
              error={errors.sku?.message}
              {...register('sku')}
            />
            <TextField
              label="Barcode"
              hint="Optional EAN/UPC/GTIN; unique when set."
              className="font-mono"
              maxLength={64}
              error={errors.barcode?.message}
              {...register('barcode')}
            />
          </div>
        </Card>

        <Card title="Pricing and inventory">
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField
              label={selected ? `Price (${selected.currency})` : 'Price'}
              required
              inputMode="decimal"
              error={errors.price?.message}
              {...register('price')}
            />
            <TextField
              label="Compare-at price"
              inputMode="decimal"
              hint="Optional original price, shown struck through; must be higher than the price."
              error={errors.compareAtPrice?.message}
              {...register('compareAtPrice')}
            />
            <TextField
              label="Stock quantity"
              required
              inputMode="numeric"
              error={errors.stockQuantity?.message}
              {...register('stockQuantity')}
            />
            <SelectField label="Status" error={errors.status?.message} {...register('status')}>
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </SelectField>
          </div>
        </Card>

        {selected && selected.images.length > 0 && (
          <Card title="Image" description="Optionally show one of the product's images for this variant.">
            <SelectField label="Variant image" error={errors.imageId?.message} {...register('imageId')}>
              <option value="">No specific image</option>
              {selected.images.map((image, index) => (
                <option key={image.id} value={image.id}>
                  {image.altText || `Image ${index + 1}`} — {image.url}
                </option>
              ))}
            </SelectField>
          </Card>
        )}

        <Card
          title="Variant attributes"
          description="Extra specifications of this variant from the attributes of the product's category. Leave a field empty when the variant does not differ."
        >
          {!productId ? (
            <p className="text-sm text-slate-500">Choose a product to see its attributes.</p>
          ) : specs.error ? (
            <ErrorState message={specs.error} onRetry={specs.reload} />
          ) : !specs.definitions || !selected ? (
            <LoadingBlock label="Loading attributes" />
          ) : (
            <SpecFields
              definitions={specs.definitions}
              original={original}
              registerSpec={(slug) => register(`specs.${slug}`)}
              errorFor={(slug) => errors.specs?.[slug]?.message}
              enforceRequired={false}
              ownCategoryId={categoryId}
            />
          )}
        </Card>

        <Card>
          <AttributesEditor
            control={control}
            register={register}
            errors={errors}
            watchTypes={attributeRows}
            legend="Other attributes"
            description="Anything else that distinguishes this variant."
          />
        </Card>

        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={() => navigate(back.to)} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting}>
            {mode === 'create' ? 'Create variant' : 'Save changes'}
          </Button>
        </div>
      </form>
    </div>
  )
}
