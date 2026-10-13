import { Link } from 'react-router'
import { attributeApi, categoryApi } from '../../api/endpoints'
import type { AttributeDataType, CatalogStatus } from '../../api/types'
import { useAuth } from '../../auth/authContext'
import { Badge } from '../../components/ui/Badge'
import { Card } from '../../components/ui/Card'
import { SelectField, TextField } from '../../components/ui/FormField'
import { Pagination } from '../../components/ui/Pagination'
import { ListBody, PageHeader, StatusToggle } from '../../features/catalog/CatalogUi'
import { useDebouncedSearch, useListParams, useLoader } from '../../features/catalog/hooks'
import { deactivateHints } from '../../features/catalog/messages'
import { linkClass, table } from '../../features/catalog/styles'
import { StatusBadge } from '../../features/products/badges'
import { humanize } from '../../lib/format'

const PAGE_SIZE = 20
const DATA_TYPES: AttributeDataType[] = ['STRING', 'NUMBER', 'BOOLEAN', 'SELECT']
const SORT_OPTIONS = [
  { value: 'name,asc', label: 'Name (A–Z)' },
  { value: 'name,desc', label: 'Name (Z–A)' },
  { value: 'dataType,asc', label: 'Type' },
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'updatedAt,desc', label: 'Recently updated' },
]

function optionsPreview(options: string[]): string {
  if (options.length <= 4) return options.join(', ')
  return `${options.slice(0, 4).join(', ')} +${options.length - 4} more`
}

export function AttributeListPage() {
  const { isAdmin } = useAuth()
  const { params, updateParams, page } = useListParams()
  const q = params.get('q') ?? ''
  const status = (params.get('status') ?? '') as CatalogStatus | ''
  const categoryId = params.get('categoryId') ?? ''
  const dataType = (params.get('dataType') ?? '') as AttributeDataType | ''
  const filterable = params.get('filterable') ?? ''
  const sort = params.get('sort') ?? 'name,asc'
  const [searchInput, setSearchInput] = useDebouncedSearch(q, updateParams)

  const categories = useLoader((signal) => categoryApi.options(true, signal), 'options')
  const list = useLoader(
    (signal) =>
      attributeApi.list(
        {
          q,
          status,
          categoryId,
          dataType,
          filterable: filterable === '' ? undefined : filterable === 'true',
          page,
          size: PAGE_SIZE,
          sort,
        },
        signal,
      ),
    JSON.stringify([q, status, categoryId, dataType, filterable, sort, page]),
  )
  const filtersActive = Boolean(q || status || categoryId || dataType || filterable)

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader
        title="Product attributes"
        description="Typed specifications such as RAM or size. An attribute applies to products in its category and all subcategories."
        actions={
          isAdmin && (
            <Link to="/product-attributes/new" className={linkClass.primary}>
              Add attribute
            </Link>
          )
        }
      />

      <Card>
        <form
          className="grid gap-4 md:grid-cols-3 xl:grid-cols-[2fr_1.5fr_1fr_1fr_1fr_1fr]"
          role="search"
          aria-label="Filter attributes"
          onSubmit={(event) => {
            event.preventDefault()
            updateParams({ q: searchInput.trim() || null })
          }}
        >
          <TextField
            label="Search"
            type="search"
            placeholder="Name or slug"
            value={searchInput}
            maxLength={100}
            onChange={(event) => setSearchInput(event.target.value)}
          />
          <SelectField
            label="Category"
            value={categoryId}
            onChange={(event) => updateParams({ categoryId: event.target.value })}
          >
            <option value="">All categories</option>
            {(categories.data ?? []).map((option) => (
              <option key={option.id} value={option.id}>
                {option.path}
              </option>
            ))}
          </SelectField>
          <SelectField label="Type" value={dataType} onChange={(event) => updateParams({ dataType: event.target.value })}>
            <option value="">All types</option>
            {DATA_TYPES.map((type) => (
              <option key={type} value={type}>
                {humanize(type)}
              </option>
            ))}
          </SelectField>
          <SelectField
            label="Filterable"
            value={filterable}
            onChange={(event) => updateParams({ filterable: event.target.value })}
          >
            <option value="">Any</option>
            <option value="true">Filterable</option>
            <option value="false">Not filterable</option>
          </SelectField>
          <SelectField label="Status" value={status} onChange={(event) => updateParams({ status: event.target.value })}>
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
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
            label="attributes"
            filtersActive={filtersActive}
            onClearFilters={() => {
              setSearchInput('')
              updateParams({ q: null, status: null, categoryId: null, dataType: null, filterable: null })
            }}
            emptyDescription={
              isAdmin ? 'Add an attribute to describe products in a category.' : 'No attributes have been created yet.'
            }
          >
            {(items) => (
              <div className="overflow-x-auto">
                <table className={table.table}>
                  <caption className="sr-only">Product attributes</caption>
                  <thead className={table.head}>
                    <tr>
                      <th scope="col" className={table.th}>Attribute</th>
                      <th scope="col" className={table.th}>Category</th>
                      <th scope="col" className={table.th}>Type</th>
                      <th scope="col" className={table.th}>Options</th>
                      <th scope="col" className={table.th}>Flags</th>
                      <th scope="col" className={table.th}>Status</th>
                      {isAdmin && (
                        <th scope="col" className={`${table.th} text-right`}>
                          <span className="sr-only">Actions</span>
                        </th>
                      )}
                    </tr>
                  </thead>
                  <tbody className={table.body}>
                    {items.map((attribute) => (
                      <tr key={attribute.id} className={table.row}>
                        <td className={table.td}>
                          <Link to={`/product-attributes/${attribute.id}`} className={linkClass.text}>
                            {attribute.name}
                          </Link>
                          <div className="font-mono text-xs text-slate-500">{attribute.slug}</div>
                        </td>
                        <td className={table.td}>
                          <Link to={`/categories/${attribute.categoryId}`} className="text-slate-700 hover:underline">
                            {attribute.categoryName}
                          </Link>
                        </td>
                        <td className={`${table.td} whitespace-nowrap`}>
                          {humanize(attribute.dataType)}
                          {attribute.unit && <span className="text-slate-500"> · {attribute.unit}</span>}
                        </td>
                        <td className={`${table.td} max-w-xs text-slate-600`}>
                          {attribute.options.length > 0 ? optionsPreview(attribute.options) : <span className="text-slate-400">—</span>}
                        </td>
                        <td className={table.td}>
                          <div className="flex flex-wrap gap-1">
                            {attribute.required && <Badge tone="indigo">Required</Badge>}
                            {attribute.filterable && <Badge tone="blue">Filterable</Badge>}
                          </div>
                        </td>
                        <td className={table.td}>
                          <StatusBadge status={attribute.status} />
                        </td>
                        {isAdmin && (
                          <td className={`${table.td} whitespace-nowrap text-right`}>
                            <div className="flex justify-end gap-1">
                              <Link
                                to={`/product-attributes/${attribute.id}/edit`}
                                className={linkClass.row}
                                aria-label={`Edit ${attribute.name}`}
                              >
                                Edit
                              </Link>
                              <StatusToggle
                                name={attribute.name}
                                status={attribute.status}
                                onChange={(next) => attributeApi.setStatus(attribute.id, next)}
                                onDone={list.reload}
                                deactivateHint={deactivateHints.attribute}
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
