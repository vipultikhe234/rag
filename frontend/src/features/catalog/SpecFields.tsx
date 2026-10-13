import type { UseFormRegisterReturn } from 'react-hook-form'
import type { AttributeValue, ProductAttribute } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { SelectField, TextField } from '../../components/ui/FormField'
import { formatAttributeValue } from './attributeValues'

interface SpecFieldsProps {
  definitions: ProductAttribute[]
  original: Record<string, AttributeValue>
  registerSpec: (slug: string) => UseFormRegisterReturn
  errorFor: (slug: string) => string | undefined
  enforceRequired: boolean
  ownCategoryId: string
  /** Ancestors of the product's category, used to name the category an attribute is inherited from. */
  ancestorIds?: string[]
}

function label(definition: ProductAttribute): string {
  return definition.unit ? `${definition.name} (${definition.unit})` : definition.name
}

function inheritedFrom(definition: ProductAttribute, ownCategoryId: string, ancestorIds: string[]): string | undefined {
  if (definition.categories.some((category) => category.id === ownCategoryId)) {
    return undefined
  }
  const sources = definition.categories.filter((category) => ancestorIds.includes(category.id))
  const names = (sources.length > 0 ? sources : definition.categories).map((category) => category.name)
  return names.length > 0 ? `Inherited from ${names.join(', ')}` : undefined
}

/** Typed inputs for the attribute definitions that apply to a category (including inherited ones). */
export function SpecFields({
  definitions,
  original,
  registerSpec,
  errorFor,
  enforceRequired,
  ownCategoryId,
  ancestorIds = [],
}: SpecFieldsProps) {
  if (definitions.length === 0) {
    return <p className="text-sm text-slate-500">No attributes are defined for this category.</p>
  }
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      {definitions.map((definition) => {
        const hint = inheritedFrom(definition, ownCategoryId, ancestorIds)
        if (definition.status !== 'ACTIVE') {
          return (
            <div key={definition.id} className="space-y-1">
              <p className="text-sm font-medium text-slate-700">
                {label(definition)} <Badge tone="gray">Inactive</Badge>
              </p>
              <p className="rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700">
                {formatAttributeValue(original[definition.slug])}
              </p>
              <p className="text-xs text-slate-500">Kept as historical data; it can no longer be changed.</p>
            </div>
          )
        }
        const common = {
          label: label(definition),
          required: enforceRequired && definition.required,
          error: errorFor(definition.slug),
          hint,
          ...registerSpec(definition.slug),
        }
        switch (definition.dataType) {
          case 'SELECT':
            return (
              <SelectField key={definition.id} {...common}>
                <option value="">Not set</option>
                {definition.options.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </SelectField>
            )
          case 'BOOLEAN':
            return (
              <SelectField key={definition.id} {...common}>
                <option value="">Not set</option>
                <option value="true">Yes</option>
                <option value="false">No</option>
              </SelectField>
            )
          case 'NUMBER':
            return <TextField key={definition.id} inputMode="decimal" {...common} />
          default:
            return <TextField key={definition.id} maxLength={500} {...common} />
        }
      })}
    </div>
  )
}
