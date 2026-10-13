import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useForm, useWatch, type Path } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { brandApi, categoryApi, productApi } from '../api/endpoints'
import { normalizeError } from '../api/errors'
import type { AttributeValue, ProductVariant } from '../api/types'
import { useToast } from '../components/toast/toastContext'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { SelectField, TextAreaField, TextField } from '../components/ui/FormField'
import { LoadingBlock } from '../components/ui/Spinner'
import { ErrorState, InlineAlert } from '../components/ui/States'
import { PageHeader } from '../features/catalog/CatalogUi'
import { collectSpecs } from '../features/catalog/attributeValues'
import { buildCategoryTree, relativePath } from '../features/catalog/categoryTree'
import { applyServerErrors } from '../features/catalog/formErrors'
import { useLoader } from '../features/catalog/hooks'
import { SpecFields } from '../features/catalog/SpecFields'
import { linkClass } from '../features/catalog/styles'
import { useSpecDefinitions } from '../features/catalog/useSpecDefinitions'
import { AttributesEditor } from '../features/products/AttributesEditor'
import { SyncBadge } from '../features/products/badges'
import { ImagesEditor } from '../features/products/ImagesEditor'
import { OptionsEditor } from '../features/products/OptionsEditor'
import {
  effectiveCategoryId,
  emptyProductForm,
  fromProduct,
  productFieldMap,
  productFormSchema,
  rowFieldPath,
  toPayload,
  type ProductFormValues,
} from '../features/products/productForm'
import { VariantBuilder } from '../features/products/VariantBuilder'
import { AMOUNT, isStale, optionDefinitions, splitValues } from '../features/products/variants'
import { formatDateTime, formatPrice } from '../lib/format'

const NO_ATTRIBUTES: Record<string, AttributeValue> = {}
const NO_VARIANTS: ProductVariant[] = []

interface ProductFormPageProps {
  mode: 'create' | 'edit'
}

function Section({ step, title, description, children }: { step: number; title: string; description?: string; children: ReactNode }) {
  return (
    <Card
      title={
        <span className="flex items-center gap-2">
          <span className="flex h-6 w-6 items-center justify-center rounded-full bg-indigo-100 text-xs font-semibold text-indigo-700">
            {step}
          </span>
          {title}
        </span>
      }
      description={description}
    >
      {children}
    </Card>
  )
}

function ReviewRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap justify-between gap-2 border-b border-slate-100 py-2 text-sm last:border-0">
      <dt className="text-slate-500">{label}</dt>
      <dd className="text-right font-medium text-slate-900">{children}</dd>
    </div>
  )
}

/** The quoted value in a conflict message such as "SKU 'ABC-1' is already used ...". */
function quotedValue(message: string): string | null {
  return /'([^']+)'/.exec(message)?.[1] ?? null
}

export function ProductFormPage({ mode }: ProductFormPageProps) {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [serverError, setServerError] = useState<string | null>(null)

  const record = useLoader((signal) => (id ? productApi.get(id, signal) : Promise.resolve(null)), id ?? 'new')
  const categories = useLoader((signal) => categoryApi.options(true, signal), 'categories')
  const brands = useLoader((signal) => brandApi.options(true, signal), 'brands')
  const product = record.data
  const existingVariants = useLoader(
    (signal) => (id && product?.hasVariants ? productApi.variants(id, signal) : Promise.resolve(NO_VARIANTS)),
    `${id ?? 'new'}:${product?.hasVariants ? product.version : 'simple'}`,
  )
  const existing = existingVariants.data ?? NO_VARIANTS

  const {
    register,
    control,
    handleSubmit,
    reset,
    setError,
    getValues,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<ProductFormValues>({
    resolver: zodResolver(productFormSchema),
    defaultValues: emptyProductForm,
    mode: 'onBlur',
  })
  const watched = useWatch({ control })
  const attributeRows = useWatch({ control, name: 'attributes' })
  const rootId = useWatch({ control, name: 'categoryId' })
  const subcategoryId = useWatch({ control, name: 'subcategoryId' })
  const hasVariants = useWatch({ control, name: 'hasVariants' })
  const categoryId = effectiveCategoryId({ categoryId: rootId, subcategoryId })

  useEffect(() => {
    if (product && categories.data) reset(fromProduct(product, categories.data))
  }, [product, categories.data, reset])

  const original = product?.attributes ?? NO_ATTRIBUTES
  const specs = useSpecDefinitions(categoryId, original, getValues, setValue)

  // Option links must point to attributes of the current category; drop links that no longer apply.
  useEffect(() => {
    if (!specs.definitions) return
    const ids = new Set(specs.definitions.map((definition) => definition.id))
    getValues('options').forEach((option, index) => {
      if (option.attributeId && !ids.has(option.attributeId)) setValue(`options.${index}.attributeId`, '')
    })
  }, [specs.definitions, getValues, setValue])

  const tree = useMemo(() => buildCategoryTree(categories.data ?? []), [categories.data])
  const stored = useMemo(
    () => (product && categories.data ? fromProduct(product, categories.data) : null),
    [product, categories.data],
  )
  // Only assignable categories (active, under active parents) can be chosen; the stored ones stay selectable.
  const rootChoices = tree.roots.filter((option) => tree.assignable(option.id) || option.id === stored?.categoryId)
  const root = rootId ? tree.byId.get(rootId) : undefined
  const subChoices = rootId
    ? tree.descendants(rootId).filter((option) => tree.assignable(option.id) || option.id === stored?.subcategoryId)
    : []
  const brandChoices = useMemo(
    () => (brands.data ?? []).filter((o) => o.status === 'ACTIVE' || o.id === product?.brandId),
    [brands.data, product],
  )

  const handleConflict = (code: string, message: string): boolean => {
    const value = quotedValue(message)
    const rows = getValues('variants')
    if (code === 'SKU_ALREADY_EXISTS' && value) {
      if (value.toUpperCase() === getValues('sku').trim().toUpperCase()) {
        setError('sku', { message }, { shouldFocus: true })
        return true
      }
      const index = rows.findIndex((row) => row.sku.trim().toUpperCase() === value.toUpperCase())
      if (index >= 0) {
        setError(`variants.${index}.sku`, { message }, { shouldFocus: true })
        return true
      }
    }
    if (code === 'BARCODE_ALREADY_EXISTS' && value) {
      const index = rows.findIndex((row) => row.barcode.trim() === value)
      if (index >= 0) {
        setError(`variants.${index}.barcode`, { message }, { shouldFocus: true })
        return true
      }
    }
    return false
  }

  const onSubmit = async (values: ProductFormValues) => {
    setServerError(null)
    if (!specs.definitions) {
      setServerError('The attributes for this category have not loaded yet. Wait a moment and try again.')
      return
    }
    if (values.hasVariants) {
      const definitions = optionDefinitions(values.options, specs.definitions)
      if (values.variants.some((row) => isStale(row.options, definitions))) {
        setError('variants', { message: 'Some new variants no longer match the options. Remove them and generate again.' })
        return
      }
      if (values.variants.length === 0 && existing.length === 0) {
        setError('variants', { message: 'Generate at least one variant: a variant product is sold through its variants.' })
        return
      }
    }
    const { attributes, fieldErrors } = collectSpecs(specs.definitions, values.specs, values.attributes, original, true)
    if (fieldErrors.length > 0) {
      fieldErrors.forEach(([path, message]) => setError(path as Path<ProductFormValues>, { message }))
      setServerError('Please correct the highlighted fields.')
      return
    }
    try {
      const payload = toPayload(values, attributes, product?.version)
      const saved = mode === 'create' ? await productApi.create(payload) : await productApi.update(id!, payload)
      toast.success(
        mode === 'create'
          ? `${saved.name} created. It will be available in AI Chat once indexing completes.`
          : `${saved.name} saved.`,
      )
      navigate(`/products/${saved.id}`)
    } catch (err) {
      const normalized = normalizeError(err)
      if (handleConflict(normalized.code, normalized.message)) {
        setServerError('Please correct the highlighted fields.')
        return
      }
      const typedSlugs = new Set(specs.definitions.filter((d) => d.status === 'ACTIVE').map((d) => d.slug))
      setServerError(
        applyServerErrors(err, setError, {
          fields: productFieldMap,
          conflicts: {
            SKU_ALREADY_EXISTS: 'sku',
            SLUG_ALREADY_EXISTS: 'slug',
            PRODUCT_HAS_VARIANTS: 'hasVariants',
            VARIANT_COMBINATION_EXISTS: 'variants',
            OPTION_IN_USE: 'options',
            OPTION_VALUE_IN_USE: 'options',
          },
          rowField: (field) => rowFieldPath(field) as Path<ProductFormValues> | null,
          specField: (slug) => (typedSlugs.has(slug) ? (`specs.${slug}` as Path<ProductFormValues>) : null),
        }),
      )
    }
  }

  // Wait for the select options too, so the selects can show the stored values once the form is filled.
  if ((mode === 'edit' && record.loading) || categories.loading || brands.loading) {
    return <LoadingBlock label="Loading product" />
  }
  if (mode === 'edit' && (record.error || !product)) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <PageHeader title="Edit product" back={{ to: '/products', label: 'Back to products' }} />
        <ErrorState message={record.error ?? 'The product was not found.'} onRetry={record.reload} />
      </div>
    )
  }

  const back = product ? { to: `/products/${product.id}`, label: 'Back to product' } : { to: '/products', label: 'Back to products' }
  const currency = (watched.currency ?? 'INR').toUpperCase()
  const newRows = watched.variants ?? []
  const rowPrices = newRows.map((row) => row?.price?.trim() ?? '').filter((p) => AMOUNT.test(p)).map(Number)
  const allPrices = [...rowPrices, ...existing.filter((v) => v.status === 'ACTIVE').map((v) => v.price)]
  const category = categoryId ? tree.byId.get(categoryId) : undefined
  const brand = brandChoices.find((option) => option.id === watched.brandId)
  const optionSummary = (watched.options ?? [])
    .filter((option) => option?.name?.trim())
    .map((option) => `${option!.name!.trim()} (${splitValues(option!.values ?? '').length})`)
    .join(', ')
  const lockedToVariants = product?.hasVariants === true && existing.length > 0

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <PageHeader
        title={mode === 'create' ? 'Add product' : `Edit ${product?.name ?? 'product'}`}
        back={back}
        description={
          product && (
            <span className="flex flex-wrap items-center gap-3 text-xs">
              <span>Last updated {formatDateTime(product.updatedAt)}</span>
              <span className="flex items-center gap-1">
                AI index: <SyncBadge sync={product.vectorSync} />
              </span>
            </span>
          )
        }
        actions={
          product && (
            <Link to={`/products/${product.id}?tab=variants`} className={linkClass.secondary}>
              Manage variants
            </Link>
          )
        }
      />

      {serverError && <InlineAlert tone="red">{serverError}</InlineAlert>}
      {(categories.error || brands.error) && (
        <InlineAlert tone="amber">
          {categories.error ? `Categories could not be loaded: ${categories.error}` : `Brands could not be loaded: ${brands.error}`}
        </InlineAlert>
      )}

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-6">
        <Section step={1} title="Basic information">
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField label="Name" required maxLength={200} error={errors.name?.message} {...register('name')} />
            <TextField
              label="SKU"
              required
              hint="Stored in upper case. Must be unique across products and variants."
              className="font-mono uppercase"
              error={errors.sku?.message}
              {...register('sku')}
            />
            <TextField
              label="Slug"
              className="font-mono"
              maxLength={200}
              hint="URL key such as galaxy-s24. Leave empty to derive it from the name."
              error={errors.slug?.message}
              {...register('slug')}
            />
            <SelectField label="Brand" error={errors.brandId?.message} {...register('brandId')}>
              <option value="">No brand</option>
              {brandChoices.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.name}
                  {option.status === 'INACTIVE' ? ' (inactive)' : ''}
                </option>
              ))}
            </SelectField>
            <div className="sm:col-span-2">
              <TextAreaField
                label="Description"
                required
                rows={5}
                hint="Used for AI search. Describe features, use cases and key specifications."
                error={errors.description?.message}
                {...register('description')}
              />
            </div>
          </div>
        </Section>

        <Section
          step={2}
          title="Category"
          description="Only active categories under active parents can be chosen. Changing the category resets the subcategory."
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <SelectField
              label="Category"
              required
              error={errors.categoryId?.message}
              {...register('categoryId', { onChange: () => setValue('subcategoryId', '') })}
            >
              <option value="">Select a category</option>
              {rootChoices.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.name}
                  {!tree.assignable(option.id) ? ' (inactive)' : ''}
                </option>
              ))}
            </SelectField>
            <SelectField
              label="Subcategory"
              disabled={!rootId || subChoices.length === 0}
              hint={
                !rootId
                  ? 'Choose a category first.'
                  : subChoices.length === 0
                    ? 'This category has no subcategories.'
                    : 'Optional. The product is assigned to the most specific choice.'
              }
              error={errors.subcategoryId?.message}
              {...register('subcategoryId')}
            >
              <option value="">{root ? `None (use ${root.name})` : 'None'}</option>
              {root &&
                subChoices.map((option) => (
                  <option key={option.id} value={option.id}>
                    {relativePath(option, root)}
                    {!tree.assignable(option.id) ? ' (inactive)' : ''}
                  </option>
                ))}
            </SelectField>
          </div>
        </Section>

        <Section
          step={3}
          title="Specifications"
          description="Attributes defined for the selected category and its parent categories. Required ones must be filled in."
        >
          {!categoryId ? (
            <p className="text-sm text-slate-500">Choose a category to see its attributes.</p>
          ) : specs.error ? (
            <ErrorState message={specs.error} onRetry={specs.reload} />
          ) : !specs.definitions ? (
            <LoadingBlock label="Loading attributes" />
          ) : (
            <SpecFields
              definitions={specs.definitions}
              original={original}
              registerSpec={(slug) => register(`specs.${slug}`)}
              errorFor={(slug) => errors.specs?.[slug]?.message}
              enforceRequired
              ownCategoryId={categoryId}
              ancestorIds={tree.ancestors(categoryId)}
            />
          )}
          <div className="mt-6 border-t border-slate-100 pt-4">
            <AttributesEditor
              control={control}
              register={register}
              errors={errors}
              watchTypes={attributeRows}
              legend="Additional attributes"
              description="Other key facts not covered by the specifications above. All attributes are included in the AI search index."
            />
          </div>
        </Section>

        <Section step={4} title="Pricing and inventory">
          <fieldset className="mb-4 space-y-2">
            <legend className="text-sm font-medium text-slate-700">Product type</legend>
            <div className="grid gap-3 sm:grid-cols-2">
              {[
                { value: false, label: 'Simple product', hint: 'One SKU with its own price and stock.' },
                {
                  value: true,
                  label: 'Product with variants',
                  hint: 'Options such as color or size; each combination has its own SKU, price and stock.',
                },
              ].map((choice) => (
                <label
                  key={String(choice.value)}
                  className={`flex cursor-pointer items-start gap-3 rounded-md border p-3 text-sm ${
                    hasVariants === choice.value ? 'border-indigo-500 bg-indigo-50' : 'border-slate-200'
                  } ${lockedToVariants && !choice.value ? 'cursor-not-allowed opacity-50' : ''}`}
                >
                  <input
                    type="radio"
                    name="productType"
                    className="mt-0.5"
                    checked={hasVariants === choice.value}
                    disabled={lockedToVariants && !choice.value}
                    onChange={() => setValue('hasVariants', choice.value, { shouldDirty: true })}
                  />
                  <span>
                    <span className="font-medium text-slate-800">{choice.label}</span>
                    <span className="block text-xs text-slate-500">{choice.hint}</span>
                  </span>
                </label>
              ))}
            </div>
            {lockedToVariants && (
              <p className="text-xs text-slate-500">This product has variants, so it cannot become a simple product.</p>
            )}
            {errors.hasVariants?.message && <p className="text-xs text-red-600">{errors.hasVariants.message}</p>}
          </fieldset>
          <div className="grid gap-4 sm:grid-cols-4">
            {!hasVariants && (
              <>
                <div className="sm:col-span-2">
                  <TextField label="Price" required inputMode="decimal" error={errors.price?.message} {...register('price')} />
                </div>
                <TextField
                  label="Stock quantity"
                  required
                  inputMode="numeric"
                  error={errors.stockQuantity?.message}
                  {...register('stockQuantity')}
                />
              </>
            )}
            <TextField
              label="Currency"
              required
              maxLength={3}
              className="uppercase"
              error={errors.currency?.message}
              {...register('currency')}
            />
            <div className="sm:col-span-2">
              <SelectField
                label="Status"
                hint="Inactive products are hidden from AI Chat answers."
                error={errors.status?.message}
                {...register('status')}
              >
                <option value="ACTIVE">Active</option>
                <option value="INACTIVE">Inactive</option>
              </SelectField>
            </div>
          </div>
          {hasVariants && (
            <p className="mt-3 text-sm text-slate-500">
              The product shows the lowest active variant price and the total stock of its active variants; set prices and
              stock per variant in step 6.
            </p>
          )}
        </Section>

        <Section step={5} title="Images" description="Up to 20 image URLs; the first one is the main image.">
          <ImagesEditor control={control} register={register} errors={errors} />
        </Section>

        {hasVariants && (
          <Section
            step={6}
            title="Options and variants"
            description="Define the options, generate the combinations, and review every variant before saving."
          >
            <div className="space-y-6">
              <OptionsEditor
                control={control}
                register={register}
                setValue={setValue}
                errors={errors}
                attributes={specs.definitions ?? []}
              />
              <div className="border-t border-slate-100 pt-4">
                {existingVariants.error ? (
                  <ErrorState message={existingVariants.error} onRetry={existingVariants.reload} />
                ) : (
                  <VariantBuilder
                    control={control}
                    register={register}
                    errors={errors}
                    attributes={specs.definitions ?? []}
                    existing={existing}
                    currency={currency}
                  />
                )}
              </div>
            </div>
          </Section>
        )}

        <Section step={hasVariants ? 7 : 6} title="Review and save" description="Everything is saved together in one step.">
          <dl>
            <ReviewRow label="Product">
              {watched.name?.trim() || '—'} <span className="font-mono text-xs text-slate-500">{watched.sku?.trim().toUpperCase()}</span>
            </ReviewRow>
            <ReviewRow label="Category">{category?.path ?? '—'}</ReviewRow>
            <ReviewRow label="Brand">{brand?.name ?? 'No brand'}</ReviewRow>
            <ReviewRow label="Type">{hasVariants ? 'Product with variants' : 'Simple product'}</ReviewRow>
            {hasVariants ? (
              <>
                <ReviewRow label="Options">{optionSummary || '—'}</ReviewRow>
                <ReviewRow label="Variants">
                  {existing.length > 0 && `${existing.length} existing + `}
                  {newRows.length} new
                </ReviewRow>
                <ReviewRow label="Price range">
                  {allPrices.length > 0
                    ? `${formatPrice(Math.min(...allPrices), currency)} – ${formatPrice(Math.max(...allPrices), currency)}`
                    : '—'}
                </ReviewRow>
              </>
            ) : (
              <ReviewRow label="Price and stock">
                {AMOUNT.test(watched.price?.trim() ?? '') ? formatPrice(Number(watched.price), currency) : '—'} ·{' '}
                {watched.stockQuantity || 0} in stock
              </ReviewRow>
            )}
            <ReviewRow label="Images">{(watched.images ?? []).length}</ReviewRow>
            <ReviewRow label="Status">{watched.status === 'INACTIVE' ? 'Inactive' : 'Active'}</ReviewRow>
          </dl>
          {Object.keys(errors).length > 0 && (
            <p className="mt-3 text-sm text-red-600" role="alert">
              Some fields need attention; they are highlighted above.
            </p>
          )}
          <div className="mt-4 flex justify-end gap-3">
            <Button variant="secondary" onClick={() => navigate(back.to)} disabled={isSubmitting}>
              Cancel
            </Button>
            <Button type="submit" loading={isSubmitting}>
              {mode === 'create' ? 'Create product' : 'Save changes'}
            </Button>
          </div>
        </Section>
      </form>
    </div>
  )
}
