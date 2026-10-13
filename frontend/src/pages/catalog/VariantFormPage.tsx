import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useState } from 'react'
import { useForm, useWatch, type Path } from 'react-hook-form'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { z } from 'zod'
import { productApi, variantApi } from '../../api/endpoints'
import type { AttributeValue, ProductVariant } from '../../api/types'
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
import { useSpecDefinitions } from '../../features/catalog/useSpecDefinitions'
import { statusField } from '../../features/catalog/validation'
import { AttributesEditor } from '../../features/products/AttributesEditor'
import { attributeRowsSchema, rowsToAttributes, toRow } from '../../features/products/productForm'

const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/
const AMOUNT_MESSAGE = 'Enter a non-negative amount with at most 2 decimals'
const NO_ATTRIBUTES: Record<string, AttributeValue> = {}

const schema = z
  .object({
    productId: z.string().min(1, 'Product is required'),
    sku: z
      .string()
      .trim()
      .min(1, 'SKU is required')
      .regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, "1-64 letters, digits, '.', '_' or '-', starting with a letter or digit"),
    variantName: z.string().trim().min(1, 'Variant name is required').max(200, 'At most 200 characters'),
    price: z.string().trim().min(1, 'Price is required').regex(AMOUNT, AMOUNT_MESSAGE),
    salePrice: z
      .string()
      .trim()
      .refine((value) => value === '' || AMOUNT.test(value), AMOUNT_MESSAGE),
    stockQuantity: z
      .string()
      .trim()
      .min(1, 'Stock quantity is required')
      .regex(/^\d+$/, 'Enter a whole number of 0 or more')
      .refine((value) => Number(value) <= 1_000_000_000, 'Stock quantity is too large'),
    status: statusField,
    specs: z.record(z.string(), z.string()),
    attributes: attributeRowsSchema,
  })
  .superRefine((values, ctx) => {
    if (values.salePrice !== '' && AMOUNT.test(values.salePrice) && AMOUNT.test(values.price)) {
      if (Number(values.salePrice) >= Number(values.price)) {
        ctx.addIssue({ code: 'custom', path: ['salePrice'], message: 'Sale price must be lower than the price' })
      }
    }
  })
type FormValues = z.infer<typeof schema>

function toForm(variant: ProductVariant): FormValues {
  return {
    productId: variant.productId,
    sku: variant.sku,
    variantName: variant.variantName,
    price: String(variant.price),
    salePrice: variant.salePrice === null ? '' : String(variant.salePrice),
    stockQuantity: String(variant.stockQuantity),
    status: variant.status,
    specs: {},
    attributes: Object.entries(variant.attributes ?? {}).map(([key, value]) => toRow(key, value)),
  }
}

const FIELD_MAP: Record<string, Path<FormValues>> = {
  productId: 'productId',
  sku: 'sku',
  variantName: 'variantName',
  price: 'price',
  salePrice: 'salePrice',
  stockQuantity: 'stockQuantity',
  status: 'status',
  attributes: 'attributes',
}

export function VariantFormPage({ mode }: { mode: 'create' | 'edit' }) {
  const { id } = useParams()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [serverError, setServerError] = useState<string | null>(null)

  const record = useLoader((signal) => (id ? variantApi.get(id, signal) : Promise.resolve(null)), id ?? 'new')
  const products = useLoader((signal) => productApi.list({ page: 0, size: 100, sort: 'name,asc' }, signal), 'products')
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
      variantName: '',
      price: '',
      salePrice: '',
      stockQuantity: '0',
      status: 'ACTIVE',
      specs: {},
      attributes: [],
    },
    mode: 'onBlur',
  })
  const productId = useWatch({ control, name: 'productId' })
  const attributeRows = useWatch({ control, name: 'attributes' })

  useEffect(() => {
    if (variant) reset(toForm(variant))
  }, [variant, reset])

  // The product list holds up to 100 products; a product beyond that is loaded on its own.
  const listed = products.data?.content.find((p) => p.id === productId)
  const single = useLoader(
    (signal) => (productId && !listed ? productApi.get(productId, signal) : Promise.resolve(null)),
    listed || !products.data ? '' : productId,
  )
  const selected = listed ?? (single.data?.id === productId ? single.data : null)
  const categoryId = selected?.categoryId ?? ''

  const original = variant?.attributes ?? NO_ATTRIBUTES
  const specs = useSpecDefinitions(categoryId, original, getValues, setValue)

  const productChoices = useMemo(() => {
    const all = products.data?.content ?? []
    return selected && !all.some((p) => p.id === selected.id) ? [selected, ...all] : all
  }, [products.data, selected])

  const onSubmit = async (values: FormValues) => {
    setServerError(null)
    if (!specs.definitions) {
      setServerError("The product's attributes have not loaded yet. Wait a moment and try again.")
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
      variantName: values.variantName.trim(),
      attributes: { ...rowsToAttributes(values.attributes), ...attributes },
      price: Number(values.price),
      salePrice: values.salePrice === '' ? null : Number(values.salePrice),
      stockQuantity: Number(values.stockQuantity),
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
          conflicts: { SKU_ALREADY_EXISTS: 'sku' },
          specField: (slug) => (typedSlugs.has(slug) ? (`specs.${slug}` as Path<FormValues>) : null),
        }),
      )
    }
  }

  // Wait for the product options too, so the select can show the stored product once the form is filled.
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
        ? { to: `/product-variants?productId=${productId}`, label: 'Back to variants' }
        : { to: '/product-variants', label: 'Back to variants' }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <PageHeader title={mode === 'create' ? 'Add variant' : `Edit ${variant?.variantName ?? 'variant'}`} back={back} />
      {serverError && <InlineAlert tone="red">{serverError}</InlineAlert>}
      {products.error && <InlineAlert tone="amber">Products could not be loaded: {products.error}</InlineAlert>}

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-6">
        <Card title="Details">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <SelectField label="Product" required error={errors.productId?.message} {...register('productId')}>
                <option value="">Select a product</option>
                {productChoices.map((product) => (
                  <option key={product.id} value={product.id}>
                    {product.name} ({product.sku}){product.status === 'INACTIVE' ? ' (inactive)' : ''}
                  </option>
                ))}
              </SelectField>
            </div>
            <TextField
              label="Variant name"
              required
              maxLength={200}
              placeholder="e.g. 256 GB / Titanium Black"
              error={errors.variantName?.message}
              {...register('variantName')}
            />
            <TextField
              label="SKU"
              required
              hint="Must be unique across products and variants."
              className="font-mono"
              error={errors.sku?.message}
              {...register('sku')}
            />
          </div>
        </Card>

        <Card title="Pricing and inventory">
          <div className="grid gap-4 sm:grid-cols-3">
            <TextField
              label={selected ? `Price (${selected.currency})` : 'Price'}
              required
              inputMode="decimal"
              error={errors.price?.message}
              {...register('price')}
            />
            <TextField
              label="Sale price"
              inputMode="decimal"
              hint="Optional; must be lower than the price."
              error={errors.salePrice?.message}
              {...register('salePrice')}
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

        <Card
          title="Variant attributes"
          description="Values that distinguish this variant, from the attributes of the product's category. Leave a field empty when the variant does not differ."
        >
          {!productId ? (
            <p className="text-sm text-slate-500">Choose a product to see its attributes.</p>
          ) : single.error && !selected ? (
            <ErrorState message={single.error} onRetry={single.reload} />
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
