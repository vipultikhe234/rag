import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useState } from 'react'
import { useForm, useWatch, type Path } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { brandApi, categoryApi, productApi } from '../api/endpoints'
import type { AttributeValue } from '../api/types'
import { useToast } from '../components/toast/toastContext'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { SelectField, TextAreaField, TextField } from '../components/ui/FormField'
import { LoadingBlock } from '../components/ui/Spinner'
import { ErrorState, InlineAlert } from '../components/ui/States'
import { PageHeader } from '../features/catalog/CatalogUi'
import { collectSpecs } from '../features/catalog/attributeValues'
import { applyServerErrors } from '../features/catalog/formErrors'
import { useLoader } from '../features/catalog/hooks'
import { SpecFields } from '../features/catalog/SpecFields'
import { linkClass } from '../features/catalog/styles'
import { useSpecDefinitions } from '../features/catalog/useSpecDefinitions'
import { AttributesEditor } from '../features/products/AttributesEditor'
import { SyncBadge } from '../features/products/badges'
import {
  emptyProductForm,
  fromProduct,
  productFieldMap,
  productFormSchema,
  toPayload,
  type ProductFormValues,
} from '../features/products/productForm'
import { formatDateTime } from '../lib/format'

const NO_ATTRIBUTES: Record<string, AttributeValue> = {}

interface ProductFormPageProps {
  mode: 'create' | 'edit'
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
  const attributeRows = useWatch({ control, name: 'attributes' })
  const categoryId = useWatch({ control, name: 'categoryId' })

  useEffect(() => {
    if (product) reset(fromProduct(product))
  }, [product, reset])

  const original = product?.attributes ?? NO_ATTRIBUTES
  const specs = useSpecDefinitions(categoryId, original, getValues, setValue)

  const categoryChoices = useMemo(
    () => (categories.data ?? []).filter((o) => o.status === 'ACTIVE' || o.id === product?.categoryId),
    [categories.data, product],
  )
  const brandChoices = useMemo(
    () => (brands.data ?? []).filter((o) => o.status === 'ACTIVE' || o.id === product?.brandId),
    [brands.data, product],
  )

  const onSubmit = async (values: ProductFormValues) => {
    setServerError(null)
    if (!specs.definitions) {
      setServerError('The attributes for this category have not loaded yet. Wait a moment and try again.')
      return
    }
    const { attributes, fieldErrors } = collectSpecs(specs.definitions, values.specs, values.attributes, original, true)
    if (fieldErrors.length > 0) {
      fieldErrors.forEach(([path, message]) => setError(path as Path<ProductFormValues>, { message }))
      setServerError('Please correct the highlighted fields.')
      return
    }
    try {
      const saved =
        mode === 'create'
          ? await productApi.create(toPayload(values, attributes))
          : await productApi.update(id!, toPayload(values, attributes, product?.version))
      toast.success(
        mode === 'create'
          ? `${saved.name} created. It will be available in AI Chat once indexing completes.`
          : `${saved.name} saved.`,
      )
      navigate('/products')
    } catch (err) {
      const typedSlugs = new Set(specs.definitions.filter((d) => d.status === 'ACTIVE').map((d) => d.slug))
      setServerError(
        applyServerErrors(err, setError, {
          fields: productFieldMap,
          conflicts: { SKU_ALREADY_EXISTS: 'sku' },
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

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <PageHeader
        title={mode === 'create' ? 'Add product' : `Edit ${product?.name ?? 'product'}`}
        back={{ to: '/products', label: 'Back to products' }}
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
            <Link to={`/product-variants?productId=${product.id}`} className={linkClass.secondary}>
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
        <Card title="Details">
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField label="Name" required error={errors.name?.message} {...register('name')} />
            <TextField
              label="SKU"
              required
              hint="Stored in upper case. Must be unique across products and variants."
              className="font-mono uppercase"
              error={errors.sku?.message}
              {...register('sku')}
            />
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
            <SelectField
              label="Category"
              required
              hint="Determines which attributes the product has."
              error={errors.categoryId?.message}
              {...register('categoryId')}
            >
              <option value="">Select a category</option>
              {categoryChoices.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.path}
                  {option.status === 'INACTIVE' ? ' (inactive)' : ''}
                </option>
              ))}
            </SelectField>
            <SelectField label="Brand" error={errors.brandId?.message} {...register('brandId')}>
              <option value="">No brand</option>
              {brandChoices.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.name}
                  {option.status === 'INACTIVE' ? ' (inactive)' : ''}
                </option>
              ))}
            </SelectField>
          </div>
        </Card>

        <Card title="Pricing and inventory">
          <div className="grid gap-4 sm:grid-cols-4">
            <div className="sm:col-span-2">
              <TextField label="Price" required inputMode="decimal" error={errors.price?.message} {...register('price')} />
            </div>
            <TextField label="Currency" required maxLength={3} className="uppercase" error={errors.currency?.message} {...register('currency')} />
            <TextField
              label="Stock quantity"
              required
              inputMode="numeric"
              error={errors.stockQuantity?.message}
              {...register('stockQuantity')}
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
        </Card>

        <Card title="Specifications" description="Attributes defined for the selected category and its parent categories.">
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
            />
          )}
        </Card>

        <Card>
          <AttributesEditor
            control={control}
            register={register}
            errors={errors}
            watchTypes={attributeRows}
            legend="Additional attributes"
            description="Other key facts not covered by the specifications above. All attributes are included in the AI search index."
          />
        </Card>

        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={() => navigate('/products')} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting}>
            {mode === 'create' ? 'Create product' : 'Save changes'}
          </Button>
        </div>
      </form>
    </div>
  )
}
