import type { CategoryOption } from '../../api/types'

export interface CategoryTree {
  byId: Map<string, CategoryOption>
  /** Active and every ancestor active: the only categories products can be newly assigned to (backend rule). */
  assignable: (id: string) => boolean
  /** Ids of the ancestors of a category, nearest first. */
  ancestors: (id: string) => string[]
  roots: CategoryOption[]
  /** All descendants of a category, in path order. */
  descendants: (id: string) => CategoryOption[]
}

export function buildCategoryTree(options: CategoryOption[]): CategoryTree {
  const byId = new Map(options.map((option) => [option.id, option]))
  const children = new Map<string, CategoryOption[]>()
  for (const option of options) {
    if (option.parentId) {
      const list = children.get(option.parentId) ?? []
      list.push(option)
      children.set(option.parentId, list)
    }
  }

  const ancestors = (id: string): string[] => {
    const result: string[] = []
    let current = byId.get(id)
    while (current?.parentId && !result.includes(current.parentId)) {
      result.push(current.parentId)
      current = byId.get(current.parentId)
    }
    return result
  }

  const assignable = (id: string): boolean => {
    const option = byId.get(id)
    if (!option || option.status !== 'ACTIVE') return false
    return ancestors(id).every((ancestorId) => byId.get(ancestorId)?.status === 'ACTIVE')
  }

  const descendants = (id: string): CategoryOption[] => {
    const result: CategoryOption[] = []
    const queue = [...(children.get(id) ?? [])]
    while (queue.length > 0) {
      const next = queue.shift()!
      if (result.some((option) => option.id === next.id)) continue
      result.push(next)
      queue.push(...(children.get(next.id) ?? []))
    }
    return result.sort((a, b) => a.path.localeCompare(b.path))
  }

  const roots = options
    .filter((option) => !option.parentId || !byId.has(option.parentId))
    .sort((a, b) => a.name.localeCompare(b.name))

  return { byId, assignable, ancestors, roots, descendants }
}

/** "Electronics / Laptops / Gaming" relative to the root -> "Laptops / Gaming". */
export function relativePath(option: CategoryOption, root: CategoryOption): string {
  const prefix = `${root.path} / `
  return option.path.startsWith(prefix) ? option.path.slice(prefix.length) : option.path
}
