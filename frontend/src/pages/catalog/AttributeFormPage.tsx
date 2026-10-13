import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { z } from 'zod'
import { attributeApi, categoryApi } from '../../api/endpoints'
import type { ProductAttribute } from '../../api/types'
import { useToast } from '../../components/toast/toastContext'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { SelectField, TextAreaField, TextField } from '../../components/ui/FormField'
import { LoadingBlock } from '../../components/ui/Spinner'
import { ErrorState, InlineAlert } from '../../components/ui/States'
import { PageHeader } from '../../features/catalog/CatalogUi'
import { applyServerErrors } from '../../features/catalog/formErrors'
import { useLoader } from '../../features/catalog/hooks'
import { nullIfBlank, slugField, statusField } from '../../features/catalog/validation'

const splitOptions = (text: string) =>
  text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')

const schema = z
  .object({
    categoryId: z.string().min(1, 'Category is required'),
    name: z.string().trim().min(1, 'Name is required').max(100, 'At most 100 characters'),
    slug: slugField(64),
    dataType: z.enum(['STRING', 'NUMBER', 'BOOLEAN', 'SELECT']),
    options: z.string(),
    unit: z.string().trim().max(20, 'At most 20 characters'),
    required: z.boolean(),
    filterable: z.boolean(),
    status: statusField,
  })
  .superRefine((values, ctx) => {
    if (values.dataType !== 'SELECT') return
    const options = splitOptions(values.options)
    const issue = (message: string) => ctx.addIssue({ code: 'custom', path: ['options'], message })
    if (options.length === 0) return issue('Select attributes need at least one option')
    if (options.length > 50) return issue('At most 50 options are allowed')
    const tooLong = options.find((option) => option.length > 100)
    if (tooLong) return issue(`"${tooLong.slice(0, 20)}…" is longer than 100 characters`)
    const seen = new Set<string>()
    for (const option of options) {
      const key = option.toLowerCase()
      if (seen.has(key)) return issue(`Duplicate option "${option}"`)
      seen.add(key)
    }
  })
type FormValues = z.infer<typeof schema>

function toForm(attribute: ProductAttribute): FormValues {
  return {
    categoryId: attribute.categoryId,
    name: attribute.name,
    slug: attribute.slug,
    dataType: attribute.dataType,
    options: attribute.options.join('\n'),
    unit: attribute.unit ?? '',
    required: attribute.required,
    filterable: attribute.filterable,
    status: attribute.status,
  }
}

function Checkbox({ label, hint, ...rest }: { label: string; hint: string } & React.ComponentProps<'input'>) {
  return (
    <label className="flex items-start gap-3 rounded-md border border-slate-200 p-3 text-sm">
      <input type="checkbox" className="mt-0.5 h-4 w-4 rounded border-slate-300 text-indigo-600" {...rest} />
      <span>
        <span className="font-medium text-slate-800">{label}</span>
        <span className="block text-xs text-slate-500">{hint}</span>
      </span>
    </label>
  )
}

export function AttributeFormPage({ mode }: { mode: 'create' | 'edit' }) {
  const { id } = useParams()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [serverError, setServerError] = useState<string | null>(null)

  const record = useLoader((signal) => (id ? attributeApi.get(id, signal) : Promise.resolve(null)), id ?? 'new')
  const categories = useLoader((signal) => categoryApi.options(true, signal), 'options')

  const {
    register,
    handleSubmit,
    reset,
    setError,
    control,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      categoryId: searchParams.get('categoryId') ?? '',
      name: '',
      slug: '',
      dataType: 'STRING',
      options: '',
      unit: '',
      required: false,
      filterable: false,
      status: 'ACTIVE',
    },
    mode: 'onBlur',
  })
  const dataType = useWatch({ control, name: 'dataType' })

  useEffect(() => {
    if (record.data) reset(toForm(record.data))
  }, [record.data, reset])

  const categoryChoices = useMemo(
    () => (categories.data ?? []).filter((o) => o.status === 'ACTIVE' || o.id === record.data?.categoryId),
    [categories.data, record.data],
  )

  const onSubmit = async (values: FormValues) => {
    setServerError(null)
    const payload = {
      categoryId: values.categoryId,
      name: values.name.trim(),
      slug: nullIfBlank(values.slug),
      dataType: values.dataType,
      options: values.dataType === 'SELECT' ? splitOptions(values.options) : [],
      unit: nullIfBlank(values.unit),
      required: values.required,
      filterable: values.filterable,
      status: values.status,
      version: record.data?.version,
    }
    try {
      const saved = mode === 'create' ? await attributeApi.create(payload) : await attributeApi.update(id!, payload)
      toast.success(mode === 'create' ? `Attribute ${saved.name} created.` : `Attribute ${saved.name} saved.`)
      navigate(`/product-attributes/${saved.id}`)
    } catch (err) {
      setServerError(
        applyServerErrors(err, setError, {
          fields: {
            categoryId: 'categoryId',
            name: 'name',
            slug: 'slug',
            dataType: 'dataType',
            options: 'options',
            unit: 'unit',
            status: 'status',
          },
        }),
      )
    }
  }

  // Wait for the category options too, so the select can show the stored category once the form is filled.
  if ((mode === 'edit' && record.loading) || categories.loading) return <LoadingBlock label="Loading attribute" />
  if (mode === 'edit' && record.error) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <PageHeader title="Edit attribute" back={{ to: '/product-attributes', label: 'Back to attributes' }} />
        <ErrorState message={record.error} onRetry={record.reload} />
      </div>
    )
  }

  const back =
    mode === 'edit' && id
      ? { to: `/product-attributes/${id}`, label: 'Back to attribute' }
      : { to: '/product-attributes', label: 'Back to attributes' }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <PageHeader title={mode === 'create' ? 'Add attribute' : `Edit ${record.data?.name ?? 'attribute'}`} back={back} />
      {serverError && <InlineAlert tone="red">{serverError}</InlineAlert>}
      {mode === 'edit' && (
        <InlineAlert tone="blue">
          Products keep their stored values when the type or options change; they are checked against the new definition
          the next time each product is saved.
        </InlineAlert>
      )}

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-6">
        <Card title="Definition">
          <div className="grid gap-4 sm:grid-cols-2">
            <SelectField
              label="Category"
              required
              hint={
                categories.error
                  ? 'Categories could not be loaded.'
                  : 'The attribute also applies to every subcategory.'
              }
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
            <SelectField label="Status" error={errors.status?.message} {...register('status')}>
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </SelectField>
            <TextField label="Name" required maxLength={100} error={errors.name?.message} {...register('name')} />
            <TextField
              label="Slug"
              className="font-mono"
              hint="Key used in product data, e.g. storage-capacity. Leave empty to derive it from the name."
              error={errors.slug?.message}
              {...register('slug')}
            />
            <SelectField label="Data type" required error={errors.dataType?.message} {...register('dataType')}>
              <option value="STRING">Text</option>
              <option value="NUMBER">Number</option>
              <option value="BOOLEAN">Yes / no</option>
              <option value="SELECT">Select (fixed options)</option>
            </SelectField>
            <TextField
              label="Unit"
              placeholder="e.g. GB, inch, kg"
              maxLength={20}
              error={errors.unit?.message}
              {...register('unit')}
            />
            {dataType === 'SELECT' && (
              <div className="sm:col-span-2">
                <TextAreaField
                  label="Options"
                  required
                  rows={5}
                  hint="One option per line (up to 50)."
                  error={errors.options?.message}
                  {...register('options')}
                />
              </div>
            )}
            <Checkbox
              label="Required"
              hint="Products in this category must have a value."
              {...register('required')}
            />
            <Checkbox
              label="Filterable"
              hint="Offer this attribute as a filter on the product list."
              {...register('filterable')}
            />
          </div>
        </Card>

        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={() => navigate(back.to)} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting} disabled={mode === 'edit' && !isDirty}>
            {mode === 'create' ? 'Create attribute' : 'Save changes'}
          </Button>
        </div>
      </form>
    </div>
  )
}
