import { useFieldArray, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form'
import { Button } from '../../components/ui/Button'
import { MAX_ATTRIBUTES, attributeTypes, type ProductFormValues } from './productForm'

interface AttributesEditorProps {
  control: Control<ProductFormValues>
  register: UseFormRegister<ProductFormValues>
  errors: FieldErrors<ProductFormValues>
  watchTypes: ProductFormValues['attributes']
}

const TYPE_LABELS: Record<(typeof attributeTypes)[number], string> = {
  text: 'Text',
  number: 'Number',
  boolean: 'Yes / No',
  list: 'List (comma separated)',
}

const INPUT =
  'block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 aria-[invalid=true]:border-red-400'

export function AttributesEditor({ control, register, errors, watchTypes }: AttributesEditorProps) {
  const { fields, append, remove } = useFieldArray({ control, name: 'attributes' })

  return (
    <fieldset className="space-y-3">
      <legend className="text-sm font-medium text-slate-700">Attributes</legend>
      <p className="text-xs text-slate-500">
        Key facts such as color, size, warranty or battery life. They are included in the AI search index.
      </p>

      {fields.length === 0 && <p className="text-sm text-slate-500">No attributes yet.</p>}

      <ul className="space-y-3">
        {fields.map((field, index) => {
          const rowErrors = errors.attributes?.[index]
          const type = watchTypes?.[index]?.type ?? 'text'
          return (
            <li key={field.id} className="grid gap-2 rounded-md border border-slate-200 p-3 sm:grid-cols-[1fr_10rem_1.5fr_auto]">
              <div>
                <label htmlFor={`attr-key-${index}`} className="sr-only">
                  Attribute {index + 1} name
                </label>
                <input
                  id={`attr-key-${index}`}
                  placeholder="Name, e.g. color"
                  aria-invalid={rowErrors?.key ? true : undefined}
                  className={INPUT}
                  {...register(`attributes.${index}.key`)}
                />
                {rowErrors?.key && <p className="mt-1 text-xs text-red-600">{rowErrors.key.message}</p>}
              </div>
              <div>
                <label htmlFor={`attr-type-${index}`} className="sr-only">
                  Attribute {index + 1} type
                </label>
                <select id={`attr-type-${index}`} className={INPUT} {...register(`attributes.${index}.type`)}>
                  {attributeTypes.map((option) => (
                    <option key={option} value={option}>
                      {TYPE_LABELS[option]}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor={`attr-value-${index}`} className="sr-only">
                  Attribute {index + 1} value
                </label>
                {type === 'boolean' ? (
                  <select
                    id={`attr-value-${index}`}
                    aria-invalid={rowErrors?.value ? true : undefined}
                    className={INPUT}
                    {...register(`attributes.${index}.value`)}
                  >
                    <option value="">Select…</option>
                    <option value="true">Yes</option>
                    <option value="false">No</option>
                  </select>
                ) : (
                  <input
                    id={`attr-value-${index}`}
                    placeholder={type === 'list' ? 'e.g. red, blue, green' : 'Value'}
                    inputMode={type === 'number' ? 'decimal' : undefined}
                    aria-invalid={rowErrors?.value ? true : undefined}
                    className={INPUT}
                    {...register(`attributes.${index}.value`)}
                  />
                )}
                {rowErrors?.value && <p className="mt-1 text-xs text-red-600">{rowErrors.value.message}</p>}
              </div>
              <div className="flex items-start">
                <Button variant="ghost" size="sm" onClick={() => remove(index)} aria-label={`Remove attribute ${index + 1}`}>
                  Remove
                </Button>
              </div>
            </li>
          )
        })}
      </ul>

      {errors.attributes?.message && <p className="text-xs text-red-600">{errors.attributes.message}</p>}
      {errors.attributes?.root?.message && <p className="text-xs text-red-600">{errors.attributes.root.message}</p>}

      <Button
        variant="secondary"
        size="sm"
        onClick={() => append({ key: '', type: 'text', value: '' })}
        disabled={fields.length >= MAX_ATTRIBUTES}
      >
        Add attribute
      </Button>
    </fieldset>
  )
}
