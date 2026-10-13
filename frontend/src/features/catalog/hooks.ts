import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { normalizeError } from '../../api/errors'

/** List filters live in the URL so they survive reloads and can be shared or linked to. */
export function useListParams() {
  const [params, setParams] = useSearchParams()

  const updateParams = useCallback(
    (changes: Record<string, string | string[] | null>) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current)
          for (const [key, value] of Object.entries(changes)) {
            next.delete(key)
            if (Array.isArray(value)) value.forEach((item) => next.append(key, item))
            else if (value !== null && value !== '') next.set(key, value)
          }
          if (!('page' in changes)) next.delete('page')
          return next
        },
        { replace: true },
      )
    },
    [setParams],
  )

  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  return { params, updateParams, page }
}

/** A search box whose value is written to the URL parameter `q` after the user stops typing. */
export function useDebouncedSearch(q: string, updateParams: (changes: Record<string, string | null>) => void) {
  const [input, setInput] = useState(q)
  useEffect(() => {
    if (input === q) return
    const timer = window.setTimeout(() => updateParams({ q: input.trim() || null }), 300)
    return () => window.clearTimeout(timer)
  }, [input, q, updateParams])
  return [input, setInput] as const
}

interface LoaderState<T> {
  data: T | null
  error: string | null
  loading: boolean
}

/**
 * Loads data whenever `key` changes (or `reload` is called), aborting the previous request. Previously loaded data
 * stays visible while a new request runs, so filters do not flash a spinner.
 */
export function useLoader<T>(load: (signal: AbortSignal) => Promise<T>, key: string) {
  const [state, setState] = useState<LoaderState<T>>({ data: null, error: null, loading: true })
  const [reloadKey, setReloadKey] = useState(0)
  const loadRef = useRef(load)

  useEffect(() => {
    loadRef.current = load
  })

  useEffect(() => {
    const controller = new AbortController()
    loadRef.current(controller.signal)
      .then((data) => setState({ data, error: null, loading: false }))
      .catch((err: unknown) => {
        if (!controller.signal.aborted) {
          setState((current) => ({ ...current, error: normalizeError(err).message, loading: false }))
        }
      })
    return () => controller.abort()
  }, [key, reloadKey])

  const reload = useCallback(() => setReloadKey((value) => value + 1), [])
  return { ...state, reload }
}
