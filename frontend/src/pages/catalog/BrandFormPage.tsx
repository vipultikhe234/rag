import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate, useParams } from 'react-router'
import { z } from 'zod'
import { brandApi } from '../../api/endpoints'
import type { Brand } from '../../api/types'
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
  description: z.string().trim().max(2000, 'At most 2000 characters'),
  websiteUrl: optionalUrlField,
  logoUrl: optionalUrlField,
  status: statusField,
})
type FormValues = z.infer<typeof schema>

const EMPTY: FormValues = { name: '', slug: '', description: '', websiteUrl: '', logoUrl: '', status: 'ACTIVE' }

function toForm(brand: Brand): FormValues {
  return {
    name: brand.name,
    slug: brand.slug,
    description: brand.description ?? '',
    websiteUrl: brand.websiteUrl ?? '',
    logoUrl: brand.logoUrl ?? '',
    status: brand.status,
  }
}

export function BrandFormPage({ mode }: { mode: 'create' | 'edit' }) {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [serverError, setServerError] = useState<string | null>(null)
  const record = useLoader((signal) => (id ? brandApi.get(id, signal) : Promise.resolve(null)), id ?? 'new')

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

  const onSubmit = async (values: FormValues) => {
    setServerError(null)
    const payload = {
      name: values.name.trim(),
      slug: nullIfBlank(values.slug),
      description: nullIfBlank(values.description),
      websiteUrl: nullIfBlank(values.websiteUrl),
      logoUrl: nullIfBlank(values.logoUrl),
      status: values.status,
      version: record.data?.version,
    }
    try {
      const saved = mode === 'create' ? await brandApi.create(payload) : await brandApi.update(id!, payload)
      toast.success(mode === 'create' ? `Brand ${saved.name} created.` : `Brand ${saved.name} saved.`)
      navigate(`/brands/${saved.id}`)
    } catch (err) {
      setServerError(
        applyServerErrors(err, setError, {
          fields: { name: 'name', slug: 'slug', description: 'description', websiteUrl: 'websiteUrl', logoUrl: 'logoUrl', status: 'status' },
          conflicts: { BRAND_ALREADY_EXISTS: 'name', SLUG_ALREADY_EXISTS: 'slug' },
        }),
      )
    }
  }

  if (mode === 'edit' && record.loading) return <LoadingBlock label="Loading brand" />
  if (mode === 'edit' && record.error) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <PageHeader title="Edit brand" back={{ to: '/brands', label: 'Back to brands' }} />
        <ErrorState message={record.error} onRetry={record.reload} />
      </div>
    )
  }

  const back = mode === 'edit' && id ? { to: `/brands/${id}`, label: 'Back to brand' } : { to: '/brands', label: 'Back to brands' }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <PageHeader title={mode === 'create' ? 'Add brand' : `Edit ${record.data?.name ?? 'brand'}`} back={back} />
      {serverError && <InlineAlert tone="red">{serverError}</InlineAlert>}

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-6">
        <Card title="Details">
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField label="Name" required maxLength={100} error={errors.name?.message} {...register('name')} />
            <TextField
              label="Slug"
              className="font-mono"
              hint="Leave empty to derive it from the name."
              error={errors.slug?.message}
              {...register('slug')}
            />
            <TextField
              label="Website"
              type="url"
              placeholder="https://"
              error={errors.websiteUrl?.message}
              {...register('websiteUrl')}
            />
            <SelectField
              label="Status"
              hint="Inactive brands cannot be assigned to products."
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
                label="Logo URL"
                type="url"
                placeholder="https://"
                hint="Optional. Only link to a logo you are allowed to use."
                error={errors.logoUrl?.message}
                {...register('logoUrl')}
              />
            </div>
          </div>
        </Card>

        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={() => navigate(back.to)} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting} disabled={mode === 'edit' && !isDirty}>
            {mode === 'create' ? 'Create brand' : 'Save changes'}
          </Button>
        </div>
      </form>
    </div>
  )
}
