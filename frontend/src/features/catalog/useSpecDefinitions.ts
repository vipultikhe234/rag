import { useEffect, useMemo } from 'react'
import type { UseFormGetValues, UseFormSetValue } from 'react-hook-form'
import { attributeApi } from '../../api/endpoints'
import type { AttributeValue } from '../../api/types'
import type { AttributeRow } from '../products/productForm'
import { redistribute, visibleDefinitions } from './attributeValues'
import { useLoader } from './hooks'

export interface SpecFormShape {
  specs: Record<string, string>
  attributes: AttributeRow[]
}

/**
 * Loads the attribute definitions that apply to `categoryId` (inherited ones included) and moves values between the
 * typed inputs and the free-form rows whenever they change. `definitions` is null until the definitions for the
 * current category are loaded; forms must not submit before then, or typed values would be lost.
 *
 * @param original stored attribute values of the record being edited (must be referentially stable)
 */
export function useSpecDefinitions<T extends SpecFormShape>(
  categoryId: string,
  original: Record<string, AttributeValue>,
  getValues: UseFormGetValues<T>,
  setValue: UseFormSetValue<T>,
) {
  // Every form using this hook has `specs` and `attributes`; narrowing the generic form type keeps the paths typed.
  const get = getValues as unknown as UseFormGetValues<SpecFormShape>
  const set = setValue as unknown as UseFormSetValue<SpecFormShape>

  const loader = useLoader(
    async (signal) => ({
      categoryId,
      definitions: categoryId ? await attributeApi.applicable(categoryId, true, signal) : [],
    }),
    categoryId,
  )
  const current = loader.data?.categoryId === categoryId ? loader.data.definitions : null
  const definitions = useMemo(() => (current ? visibleDefinitions(current, original) : null), [current, original])

  useEffect(() => {
    if (!definitions) return
    const next = redistribute(definitions, get('specs') ?? {}, get('attributes') ?? [])
    set('specs', next.specs)
    set('attributes', next.rows)
  }, [definitions, get, set])

  return {
    definitions,
    loading: definitions === null && !loader.error,
    error: definitions === null ? loader.error : null,
    reload: loader.reload,
  }
}
