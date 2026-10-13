import type { ChangeEvent } from 'react'
import { useFieldArray, useWatch, type Control, type FieldErrors, type UseFormRegister, type UseFormSetValue } from 'react-hook-form'
import type { ProductAttribute } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { SelectField, TextAreaField, TextField } from '../../components/ui/FormField'
import type { ProductFormValues } from './productForm'
import { MAX_OPTION_VALUES, MAX_OPTIONS, splitValues } from './variants'

const NUMERIC = /^-?\d+(\.\d+)?$/

interface OptionsEditorProps {
  control: Control<ProductFormValues>
  register: UseFormRegister<ProductFormValues>
  setValue: UseFormSetValue<ProductFormValues>
  errors: FieldErrors<ProductFormValues>
  /** Attributes that apply to the product's category; an option can be linked to one of them. */
  attributes: ProductAttribute[]
}

/** Values that the linked attribute will reject, so the user sees it before saving. */
function attributeProblem(attribute: ProductAttribute | undefined, values: string[]): string | null {
  if (!attribute) return null
  if (attribute.dataType === 'NUMBER') {
    const bad = values.find((value) => !NUMERIC.test(value))
    return bad ? `"${bad}" is not a number (${attribute.name} is a number attribute)` : null
  }
  if (attribute.dataType === 'SELECT') {
    const allowed = new Set(attribute.options.map((option) => option.toLowerCase()))
    const bad = values.find((value) => !allowed.has(value.toLowerCase()))
    return bad ? `"${bad}" is not one of the ${attribute.name} options` : null
  }
  if (attribute.dataType === 'BOOLEAN') {
    const bad = values.find((value) => value !== 'true' && value !== 'false')
    return bad ? `${attribute.name} only accepts true or false` : null
  }
  return null
}

export function OptionsEditor({ control, register, setValue, errors, attributes }: OptionsEditorProps) {
  const { fields, append, remove } = useFieldArray({ control, name: 'options' })
  const options = useWatch({ control, name: 'options' })
  const linkable = attributes.filter((attribute) => attribute.status === 'ACTIVE')

  return (
    <div className="space-y-4">
      {fields.length === 0 && (
        <p className="text-sm text-slate-500">
          Options are the choices a customer makes, such as Color or Size. Add up to {MAX_OPTIONS}.
        </p>
      )}
      {fields.map((field, index) => {
        const row = options?.[index]
        const linked = linkable.find((attribute) => attribute.id === row?.attributeId)
          ?? attributes.find((attribute) => attribute.id === row?.attributeId)
        const values = splitValues(row?.values ?? '')
        const problem = attributeProblem(linked, values)
        return (
          <fieldset key={field.id} className="space-y-3 rounded-md border border-slate-200 p-4">
            <legend className="px-1 text-sm font-medium text-slate-700">Option {index + 1}</legend>
            <div className="grid gap-3 sm:grid-cols-2">
              <TextField
                label="Name"
                required
                placeholder="e.g. Color"
                maxLength={50}
                error={errors.options?.[index]?.name?.message}
                {...register(`options.${index}.name`)}
              />
              <SelectField
                label="Linked attribute"
                hint="Optional. Values are validated against the attribute, and number units are shown."
                error={errors.options?.[index]?.attributeId?.message}
                {...register(`options.${index}.attributeId`, {
                  onChange: (event: ChangeEvent<HTMLSelectElement>) => {
                    const attribute = linkable.find((a) => a.id === event.target.value)
                    if (attribute && !row?.name.trim()) setValue(`options.${index}.name`, attribute.name)
                  },
                })}
              >
                <option value="">Not linked</option>
                {linkable.map((attribute) => (
                  <option key={attribute.id} value={attribute.id}>
                    {attribute.name}
                    {attribute.unit ? ` (${attribute.unit})` : ''}
                  </option>
                ))}
                {linked && !linkable.includes(linked) && (
                  <option value={linked.id}>{linked.name} (inactive)</option>
                )}
              </SelectField>
            </div>
            <TextAreaField
              label={`Values (${values.length}/${MAX_OPTION_VALUES})`}
              required
              rows={2}
              placeholder="Black, White, Blue"
              hint={problem ?? 'Separate values with commas or new lines. Duplicates are ignored.'}
              error={errors.options?.[index]?.values?.message ?? problem ?? undefined}
              {...register(`options.${index}.values`)}
            />
            <div className="flex flex-wrap justify-between gap-2">
              {linked?.dataType === 'SELECT' ? (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() =>
                    setValue(`options.${index}.values`, linked.options.join(', '), {
                      shouldDirty: true,
                      shouldValidate: true,
                    })
                  }
                >
                  Use all {linked.name} values
                </Button>
              ) : (
                <span />
              )}
              <Button
                variant="ghost"
                size="sm"
                className="text-red-600 hover:bg-red-50"
                onClick={() => remove(index)}
                aria-label={`Remove option ${index + 1}`}
              >
                Remove option
              </Button>
            </div>
          </fieldset>
        )
      })}
      {(errors.options?.message || errors.options?.root?.message) && (
        <p className="text-xs text-red-600" role="alert">
          {errors.options?.message ?? errors.options?.root?.message}
        </p>
      )}
      <Button
        variant="secondary"
        size="sm"
        disabled={fields.length >= MAX_OPTIONS}
        onClick={() => append({ id: '', name: '', attributeId: '', values: '' })}
      >
        Add option
      </Button>
    </div>
  )
}
