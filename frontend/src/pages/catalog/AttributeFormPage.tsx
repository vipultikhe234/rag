import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useState } from 'react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { z } from 'zod'
import { attributeApi, categoryApi } from '../../api/endpoints'
import type { CategoryOption, ProductAttribute } from '../../api/types'
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
    categoryIds: z
      .array(z.string())
      .min(1, 'Choose at least one category')
      .max(20, 'An attribute can be assigned to at most 20 categories'),
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
    categoryIds: attribute.categories.map((category) => category.id),
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

interface CategoryChecklistProps {
  choices: CategoryOption[]
  value: string[]
  onChange: (next: string[]) => void
  onBlur: () => void
  error?: string
  hint: string
}

/** Searchable checkbox list; selected categories are listed first so they stay visible. */
function CategoryChecklist({ choices, value, onChange, onBlur, error, hint }: CategoryChecklistProps) {
  const [filter, setFilter] = useState('')
  const selected = new Set(value)
  const needle = filter.trim().toLowerCase()
  const visible = choices
    .filter((option) => !needle || option.path.toLowerCase().includes(needle) || selected.has(option.id))
    .sort((a, b) => Number(selected.has(b.id)) - Number(selected.has(a.id)) || a.path.localeCompare(b.path))
  const toggle = (id: string) => onChange(selected.has(id) ? value.filter((v) => v !== id) : [...value, id])

  return (
    <fieldset className="space-y-2 sm:col-span-2" aria-invalid={error ? true : undefined}>
      <legend className="text-sm font-medium text-slate-700">
        Categories <span className="text-red-600">*</span>
        <span className="ml-2 text-xs font-normal text-slate-500">{value.length} selected</span>
      </legend>
      <input
        type="search"
        value={filter}
        onChange={(event) => setFilter(event.target.value)}
        placeholder="Filter categories"
        aria-label="Filter categories"
        className="w-full rounded-md border border-slate-300 px-3 py-1.5 text-sm"
      />
      <div className="max-h-56 overflow-y-auto rounded-md border border-slate-200 p-2" onBlur={onBlur}>
        {visible.length === 0 && <p className="px-1 py-2 text-sm text-slate-500">No categories match.</p>}
        {visible.map((option) => (
          <label key={option.id} className="flex items-center gap-2 rounded px-1 py-1 text-sm hover:bg-slate-50">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-slate-300 text-indigo-600"
              checked={selected.has(option.id)}
              onChange={() => toggle(option.id)}
            />
            <span>
              {option.path}
              {option.status === 'INACTIVE' && <span className="text-slate-500"> (inactive)</span>}
            </span>
          </label>
        ))}
      </div>
      {error ? <p className="text-xs text-red-600">{error}</p> : <p className="text-xs text-slate-500">{hint}</p>}
    </fieldset>
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
      categoryIds: searchParams.get('categoryId') ? [searchParams.get('categoryId')!] : [],
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

  // Inactive categories cannot be newly assigned; ones the attribute already has stay selectable so they can be kept.
  const categoryChoices = useMemo(() => {
    const assigned = new Set(record.data?.categories.map((category) => category.id) ?? [])
    return (categories.data ?? []).filter((o) => o.status === 'ACTIVE' || assigned.has(o.id))
  }, [categories.data, record.data])

  const onSubmit = async (values: FormValues) => {
    setServerError(null)
    const payload = {
      categoryIds: values.categoryIds,
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
            categoryIds: 'categoryIds',
            categoryId: 'categoryIds',
            name: 'name',
            slug: 'slug',
            dataType: 'dataType',
            options: 'options',
            unit: 'unit',
            status: 'status',
          },
          conflicts: { ATTRIBUTE_ALREADY_EXISTS: 'name', SLUG_ALREADY_EXISTS: 'slug' },
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
            <Controller
              control={control}
              name="categoryIds"
              render={({ field }) => (
                <CategoryChecklist
                  choices={categoryChoices}
                  value={field.value}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  error={errors.categoryIds?.message ?? errors.categoryIds?.root?.message}
                  hint={
                    categories.error
                      ? 'Categories could not be loaded.'
                      : 'The attribute also applies to every subcategory of the chosen categories.'
                  }
                />
              )}
            />
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
              hint="Products in these categories must have a value."
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
