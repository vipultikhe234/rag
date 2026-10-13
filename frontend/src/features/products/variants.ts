export const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/
export const AMOUNT_MESSAGE = 'Enter a non-negative amount with at most 2 decimals'
export const SKU = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/
export const SKU_MESSAGE = "1-64 letters, digits, '.', '_' or '-', starting with a letter or digit"
export const BARCODE = /^$|^[A-Za-z0-9-]{4,64}$/
export const BARCODE_MESSAGE = "4-64 letters, digits or '-'"

/** Backend limits (ProductRequest / ProductOptionRequest). */
export const MAX_OPTIONS = 3
export const MAX_OPTION_VALUES = 50
export const MAX_VARIANTS = 100

const NUMERIC = /^-?\d+(\.\d+)?$/

/** Display label of an option value; the unit of a linked NUMBER attribute is appended, e.g. "256 GB". */
export function optionLabel(value: string, unit: string | null | undefined): string {
  return unit && NUMERIC.test(value.trim()) ? `${value.trim()} ${unit}` : value
}

export interface OptionDefinition {
  name: string
  unit?: string | null
  values: string[]
}

/** One generated combination: option name -> value, in option order. */
export type Combination = Array<{ name: string; value: string }>

export function combinationKey(combination: Combination | Record<string, string>): string {
  const entries = Array.isArray(combination)
    ? combination.map(({ name, value }) => [name, value] as const)
    : Object.entries(combination)
  return entries
    .map(([name, value]) => `${name.trim().toLowerCase()}=${value.trim().toLowerCase()}`)
    .sort()
    .join('|')
}

/** All combinations of the option values (cartesian product), in option and value order. */
export function combinations(options: OptionDefinition[]): Combination[] {
  const usable = options.filter((option) => option.name.trim() !== '' && option.values.length > 0)
  if (usable.length === 0) return []
  return usable.reduce<Combination[]>(
    (acc, option) => acc.flatMap((combo) => option.values.map((value) => [...combo, { name: option.name.trim(), value }])),
    [[]],
  )
}

export function combinationCount(options: OptionDefinition[]): number {
  const usable = options.filter((option) => option.name.trim() !== '' && option.values.length > 0)
  return usable.length === 0 ? 0 : usable.reduce((count, option) => count * option.values.length, 1)
}

export function combinationTitle(combination: Combination, options: OptionDefinition[]): string {
  return combination
    .map(({ name, value }) => optionLabel(value, options.find((option) => option.name.trim() === name)?.unit))
    .join(' / ')
}

/** Suggested SKU such as "GS24-256-BLACK"; the user can change it before saving. */
export function suggestSku(productSku: string, combination: Combination): string {
  const parts = combination.map(({ value }) =>
    value
      .trim()
      .toUpperCase()
      .replace(/[^A-Z0-9]+/g, '')
      .slice(0, 12),
  )
  return [productSku.trim(), ...parts]
    .filter((part) => part !== '')
    .join('-')
    .slice(0, 64)
}

/** Option definitions from the form's option rows; the unit comes from a linked NUMBER attribute. */
export function optionDefinitions(
  rows: Array<{ name: string; attributeId: string; values: string }> | undefined,
  attributes: Array<{ id: string; dataType: string; unit: string | null }>,
): OptionDefinition[] {
  return (rows ?? []).map((row) => {
    const attribute = attributes.find((a) => a.id === row.attributeId)
    return {
      name: row.name.trim(),
      unit: attribute?.dataType === 'NUMBER' ? attribute.unit : null,
      values: splitValues(row.values),
    }
  })
}

/** True when a generated row's options no longer match the current option names and values. */
export function isStale(rowOptions: Record<string, string>, definitions: OptionDefinition[]): boolean {
  const names = Object.keys(rowOptions)
  if (names.length !== definitions.filter((d) => d.name && d.values.length > 0).length) return true
  return names.some((name) => {
    const definition = definitions.find((d) => d.name.toLowerCase() === name.toLowerCase())
    return !definition || !definition.values.some((v) => v.toLowerCase() === rowOptions[name].toLowerCase())
  })
}

/** Splits comma- or newline-separated option values, trimming and dropping blanks and duplicates (case-insensitive). */
export function splitValues(text: string): string[] {
  const seen = new Set<string>()
  const values: string[] = []
  for (const raw of text.split(/[\n,]/)) {
    const value = raw.trim()
    const key = value.toLowerCase()
    if (value !== '' && !seen.has(key)) {
      seen.add(key)
      values.push(value)
    }
  }
  return values
}
