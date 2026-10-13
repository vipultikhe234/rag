import { Link } from 'react-router'
import { categoryApi } from '../../api/endpoints'
import type { CatalogStatus } from '../../api/types'
import { useAuth } from '../../auth/authContext'
import { Card } from '../../components/ui/Card'
import { SelectField, TextField } from '../../components/ui/FormField'
import { Pagination } from '../../components/ui/Pagination'
import { ListBody, PageHeader, StatusToggle } from '../../features/catalog/CatalogUi'
import { useDebouncedSearch, useListParams, useLoader } from '../../features/catalog/hooks'
import { deactivateHints } from '../../features/catalog/messages'
import { linkClass, table } from '../../features/catalog/styles'
import { StatusBadge } from '../../features/products/badges'

const PAGE_SIZE = 20
const SORT_OPTIONS = [
  { value: 'name,asc', label: 'Name (A–Z)' },
  { value: 'name,desc', label: 'Name (Z–A)' },
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'updatedAt,desc', label: 'Recently updated' },
]

export function CategoryListPage() {
  const { isAdmin } = useAuth()
  const { params, updateParams, page } = useListParams()
  const q = params.get('q') ?? ''
  const status = (params.get('status') ?? '') as CatalogStatus | ''
  const parent = params.get('parent') ?? ''
  const sort = params.get('sort') ?? 'name,asc'
  const [searchInput, setSearchInput] = useDebouncedSearch(q, updateParams)

  const options = useLoader((signal) => categoryApi.options(true, signal), 'options')
  const list = useLoader(
    (signal) =>
      categoryApi.list(
        {
          q,
          status,
          parentId: parent && parent !== 'root' ? parent : undefined,
          rootsOnly: parent === 'root',
          page,
          size: PAGE_SIZE,
          sort,
        },
        signal,
      ),
    JSON.stringify([q, status, parent, sort, page]),
  )

  const filtersActive = Boolean(q || status || parent)
  const reloadAll = () => {
    list.reload()
    options.reload()
  }

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader
        title="Categories"
        description="Organise products in a hierarchy. Attributes defined on a category also apply to its subcategories."
        actions={
          isAdmin && (
            <Link to="/categories/new" className={linkClass.primary}>
              Add category
            </Link>
          )
        }
      />

      <Card>
        <form
          className="grid gap-4 md:grid-cols-[2fr_1fr_1.5fr_1fr]"
          role="search"
          aria-label="Filter categories"
          onSubmit={(event) => {
            event.preventDefault()
            updateParams({ q: searchInput.trim() || null })
          }}
        >
          <TextField
            label="Search"
            type="search"
            placeholder="Name, slug or description"
            value={searchInput}
            maxLength={100}
            onChange={(event) => setSearchInput(event.target.value)}
          />
          <SelectField label="Status" value={status} onChange={(event) => updateParams({ status: event.target.value })}>
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </SelectField>
          <SelectField label="Parent" value={parent} onChange={(event) => updateParams({ parent: event.target.value })}>
            <option value="">Any parent</option>
            <option value="root">Top-level only</option>
            {(options.data ?? []).map((option) => (
              <option key={option.id} value={option.id}>
                {option.path}
              </option>
            ))}
          </SelectField>
          <SelectField label="Sort by" value={sort} onChange={(event) => updateParams({ sort: event.target.value })}>
            {SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
        </form>

        <div className="mt-6">
          <ListBody
            loading={list.loading}
            error={list.error}
            data={list.data}
            onRetry={list.reload}
            label="categories"
            filtersActive={filtersActive}
            onClearFilters={() => {
              setSearchInput('')
              updateParams({ q: null, status: null, parent: null })
            }}
            emptyDescription={isAdmin ? 'Add a category to start organising products.' : 'No categories have been created yet.'}
          >
            {(items) => (
              <div className="overflow-x-auto">
                <table className={table.table}>
                  <caption className="sr-only">Categories</caption>
                  <thead className={table.head}>
                    <tr>
                      <th scope="col" className={table.th}>Category</th>
                      <th scope="col" className={table.th}>Parent</th>
                      <th scope="col" className={`${table.th} text-right`}>Products</th>
                      <th scope="col" className={`${table.th} text-right`}>Subcategories</th>
                      <th scope="col" className={`${table.th} text-right`}>Attributes</th>
                      <th scope="col" className={table.th}>Status</th>
                      {isAdmin && (
                        <th scope="col" className={`${table.th} text-right`}>
                          <span className="sr-only">Actions</span>
                        </th>
                      )}
                    </tr>
                  </thead>
                  <tbody className={table.body}>
                    {items.map((category) => (
                      <tr key={category.id} className={table.row}>
                        <td className={table.td}>
                          <Link to={`/categories/${category.id}`} className={linkClass.text}>
                            {category.name}
                          </Link>
                          <div className="font-mono text-xs text-slate-500">{category.slug}</div>
                        </td>
                        <td className={table.td}>{category.parentName ?? <span className="text-slate-400">—</span>}</td>
                        <td className={`${table.td} text-right tabular-nums`}>{category.productCount}</td>
                        <td className={`${table.td} text-right tabular-nums`}>{category.subcategoryCount}</td>
                        <td className={`${table.td} text-right tabular-nums`}>{category.attributeCount}</td>
                        <td className={table.td}>
                          <StatusBadge status={category.status} />
                        </td>
                        {isAdmin && (
                          <td className={`${table.td} whitespace-nowrap text-right`}>
                            <div className="flex justify-end gap-1">
                              <Link
                                to={`/categories/${category.id}/edit`}
                                className={linkClass.row}
                                aria-label={`Edit ${category.name}`}
                              >
                                Edit
                              </Link>
                              <StatusToggle
                                name={category.name}
                                status={category.status}
                                onChange={(next) => categoryApi.setStatus(category.id, next)}
                                onDone={reloadAll}
                                deactivateHint={deactivateHints.category}
                              />
                            </div>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </ListBody>
          {list.data && (
            <Pagination
              page={list.data.page}
              size={list.data.size}
              totalPages={list.data.totalPages}
              totalElements={list.data.totalElements}
              onPageChange={(next) => updateParams({ page: String(next) })}
            />
          )}
        </div>
      </Card>
    </div>
  )
}
