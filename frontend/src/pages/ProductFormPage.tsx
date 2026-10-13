import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { productApi } from '../api/endpoints'
import { normalizeError } from '../api/errors'
import type { Product } from '../api/types'
import { useToast } from '../components/toast/toastContext'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { SelectField, TextAreaField, TextField } from '../components/ui/FormField'
import { LoadingBlock } from '../components/ui/Spinner'
import { ErrorState, InlineAlert } from '../components/ui/States'
import { AttributesEditor } from '../features/products/AttributesEditor'
import { SyncBadge } from '../features/products/badges'
import {
  emptyProductForm,
  formFieldNames,
  fromProduct,
  productFormSchema,
  toPayload,
  type ProductFormValues,
} from '../features/products/productForm'
import { formatDateTime } from '../lib/format'

interface ProductFormPageProps {
  mode: 'create' | 'edit'
}

export function ProductFormPage({ mode }: ProductFormPageProps) {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [product, setProduct] = useState<Product | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [loading, setLoading] = useState(mode === 'edit')
  const [serverError, setServerError] = useState<string | null>(null)
  const [categories, setCategories] = useState<string[]>([])
  const [reloadKey, setReloadKey] = useState(0)

  const {
    register,
    control,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<ProductFormValues>({
    resolver: zodResolver(productFormSchema),
    defaultValues: emptyProductForm,
    mode: 'onBlur',
  })
  const attributeRows = useWatch({ control, name: 'attributes' })

  useEffect(() => {
    productApi
      .categories()
      .then(setCategories)
      .catch(() => setCategories([]))
  }, [])

  useEffect(() => {
    if (mode !== 'edit' || !id) return
    const controller = new AbortController()
    productApi
      .get(id, controller.signal)
      .then((loaded) => {
        setProduct(loaded)
        reset(fromProduct(loaded))
        setLoadError(null)
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted) setLoadError(normalizeError(err).message)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [mode, id, reset, reloadKey])

  const onSubmit = async (values: ProductFormValues) => {
    setServerError(null)
    try {
      const saved =
        mode === 'create'
          ? await productApi.create(toPayload(values))
          : await productApi.update(id!, toPayload(values, product?.version))
      toast.success(
        mode === 'create'
          ? `${saved.name} created. It will be available in AI Chat once indexing completes.`
          : `${saved.name} saved.`,
      )
      navigate('/products')
    } catch (err) {
      const normalized = normalizeError(err)
      if (normalized.code === 'SKU_ALREADY_EXISTS') {
        setError('sku', { message: 'This SKU is already used by another product' }, { shouldFocus: true })
        return
      }
      let mapped = false
      for (const violation of normalized.fieldErrors) {
        const field = violation.field.split(/[.[]/)[0]
        if (formFieldNames.has(field)) {
          setError(field as keyof ProductFormValues, { message: violation.message })
          mapped = true
        }
      }
      setServerError(mapped ? 'Please correct the highlighted fields.' : normalized.message)
    }
  }

  if (loading) {
    return <LoadingBlock label="Loading product" />
  }
  if (loadError) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <ErrorState
          message={loadError}
          onRetry={() => {
            setLoading(true)
            setReloadKey((key) => key + 1)
          }}
        />
        <Link to="/products" className="text-sm font-medium text-indigo-600 hover:text-indigo-500">
          ← Back to products
        </Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div>
        <Link to="/products" className="text-sm font-medium text-indigo-600 hover:text-indigo-500">
          ← Back to products
        </Link>
        <h1 className="mt-2 text-2xl font-semibold text-slate-900">
          {mode === 'create' ? 'Add product' : `Edit ${product?.name ?? 'product'}`}
        </h1>
        {product && (
          <div className="mt-2 flex flex-wrap items-center gap-3 text-xs text-slate-500">
            <span>Last updated {formatDateTime(product.updatedAt)}</span>
            <span className="flex items-center gap-1">
              AI index: <SyncBadge sync={product.vectorSync} />
            </span>
          </div>
        )}
      </div>

      {serverError && <InlineAlert tone="red">{serverError}</InlineAlert>}

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-6">
        <Card title="Details">
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField label="Name" required error={errors.name?.message} {...register('name')} />
            <TextField
              label="SKU"
              required
              hint="Stored in upper case. Must be unique."
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
            <TextField
              label="Category"
              required
              list="category-options"
              error={errors.category?.message}
              {...register('category')}
            />
            <datalist id="category-options">
              {categories.map((item) => (
                <option key={item} value={item} />
              ))}
            </datalist>
            <TextField label="Brand" error={errors.brand?.message} {...register('brand')} />
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

        <Card>
          <AttributesEditor control={control} register={register} errors={errors} watchTypes={attributeRows} />
        </Card>

        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={() => navigate('/products')} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting} disabled={mode === 'edit' && !isDirty}>
            {mode === 'create' ? 'Create product' : 'Save changes'}
          </Button>
        </div>
      </form>
    </div>
  )
}
