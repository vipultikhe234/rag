import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate, useParams } from 'react-router'
import { z } from 'zod'
import { categoryApi } from '../../api/endpoints'
import type { Category, CategoryOption } from '../../api/types'
import { useToast } from '../../components/toast/toastContext'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { SelectField, TextAreaField, TextField } from '../../components/ui/FormField'
import { LoadingBlock } from '../../components/ui/Spinner'
import { ErrorState, InlineAlert } from '../../components/ui/States'
import { PageHeader } from '../../features/catalog/CatalogUi'
import { applyServerErrors } from '../../features/catalog/formErrors'
import { useLoader } from '../../features/catalog/hooks'
import { nullIfBlank, optionalUrlField, slugField, statusField } from '../../features/catalog/validation'

const schema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(100, 'At most 100 characters'),
  slug: slugField(120),
  parentId: z.string(),
  description: z.string().trim().max(2000, 'At most 2000 characters'),
  imageUrl: optionalUrlField,
  status: statusField,
})
type FormValues = z.infer<typeof schema>

const EMPTY: FormValues = { name: '', slug: '', parentId: '', description: '', imageUrl: '', status: 'ACTIVE' }

function toForm(category: Category): FormValues {
  return {
    name: category.name,
    slug: category.slug,
    parentId: category.parentId ?? '',
    description: category.description ?? '',
    imageUrl: category.imageUrl ?? '',
    status: category.status,
  }
}

/** Ids of the category and all of its descendants (they cannot become its parent). */
function selfAndDescendants(id: string | undefined, options: CategoryOption[]): Set<string> {
  const result = new Set<string>()
  if (!id) return result
  const queue = [id]
  while (queue.length > 0) {
    const current = queue.shift()!
    result.add(current)
    options.filter((o) => o.parentId === current && !result.has(o.id)).forEach((o) => queue.push(o.id))
  }
  return result
}

export function CategoryFormPage({ mode }: { mode: 'create' | 'edit' }) {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [serverError, setServerError] = useState<string | null>(null)

  const record = useLoader((signal) => (id ? categoryApi.get(id, signal) : Promise.resolve(null)), id ?? 'new')
  const options = useLoader((signal) => categoryApi.options(true, signal), 'options')

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: EMPTY, mode: 'onBlur' })

  useEffect(() => {
    if (record.data) reset(toForm(record.data))
  }, [record.data, reset])

  const parentChoices = useMemo(() => {
    const all = options.data ?? []
    const excluded = selfAndDescendants(id, all)
    const currentParent = record.data?.parentId
    return all.filter((o) => !excluded.has(o.id) && (o.status === 'ACTIVE' || o.id === currentParent))
  }, [options.data, id, record.data])

  const onSubmit = async (values: FormValues) => {
    setServerError(null)
    const payload = {
      name: values.name.trim(),
      slug: nullIfBlank(values.slug),
      description: nullIfBlank(values.description),
      imageUrl: nullIfBlank(values.imageUrl),
      parentId: values.parentId || null,
      status: values.status,
      version: record.data?.version,
    }
    try {
      const saved = mode === 'create' ? await categoryApi.create(payload) : await categoryApi.update(id!, payload)
      toast.success(mode === 'create' ? `Category ${saved.name} created.` : `Category ${saved.name} saved.`)
      navigate(`/categories/${saved.id}`)
    } catch (err) {
      setServerError(
        applyServerErrors(err, setError, {
          fields: { name: 'name', slug: 'slug', parentId: 'parentId', description: 'description', imageUrl: 'imageUrl', status: 'status' },
          conflicts: { CATEGORY_ALREADY_EXISTS: 'name', SLUG_ALREADY_EXISTS: 'slug' },
        }),
      )
    }
  }

  // Wait for the parent options too, so the select can show the stored parent once the form is filled.
  if ((mode === 'edit' && record.loading) || options.loading) return <LoadingBlock label="Loading category" />
  if (mode === 'edit' && record.error) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <PageHeader title="Edit category" back={{ to: '/categories', label: 'Back to categories' }} />
        <ErrorState message={record.error} onRetry={record.reload} />
      </div>
    )
  }

  const back = mode === 'edit' && id ? { to: `/categories/${id}`, label: 'Back to category' } : { to: '/categories', label: 'Back to categories' }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <PageHeader title={mode === 'create' ? 'Add category' : `Edit ${record.data?.name ?? 'category'}`} back={back} />
      {serverError && <InlineAlert tone="red">{serverError}</InlineAlert>}

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-6">
        <Card title="Details">
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField label="Name" required maxLength={100} error={errors.name?.message} {...register('name')} />
            <TextField
              label="Slug"
              className="font-mono"
              hint="Leave empty to derive it from the name, e.g. mens-clothing."
              error={errors.slug?.message}
              {...register('slug')}
            />
            <SelectField
              label="Parent category"
              hint={options.error ? 'Categories could not be loaded.' : 'Leave empty for a top-level category.'}
              error={errors.parentId?.message}
              {...register('parentId')}
            >
              <option value="">None (top level)</option>
              {parentChoices.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.path}
                  {option.status === 'INACTIVE' ? ' (inactive)' : ''}
                </option>
              ))}
            </SelectField>
            <SelectField
              label="Status"
              hint="Inactive categories cannot be assigned to new products."
              error={errors.status?.message}
              {...register('status')}
            >
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </SelectField>
            <div className="sm:col-span-2">
              <TextAreaField label="Description" rows={3} error={errors.description?.message} {...register('description')} />
            </div>
            <div className="sm:col-span-2">
              <TextField
                label="Image URL"
                type="url"
                placeholder="https://"
                error={errors.imageUrl?.message}
                {...register('imageUrl')}
              />
            </div>
          </div>
        </Card>

        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={() => navigate(back.to)} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting} disabled={mode === 'edit' && !isDirty}>
            {mode === 'create' ? 'Create category' : 'Save changes'}
          </Button>
        </div>
      </form>
    </div>
  )
}
