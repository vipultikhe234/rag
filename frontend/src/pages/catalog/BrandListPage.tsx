import { Link } from 'react-router'
import { brandApi } from '../../api/endpoints'
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

function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

export function BrandListPage() {
  const { isAdmin } = useAuth()
  const { params, updateParams, page } = useListParams()
  const q = params.get('q') ?? ''
  const status = (params.get('status') ?? '') as CatalogStatus | ''
  const sort = params.get('sort') ?? 'name,asc'
  const [searchInput, setSearchInput] = useDebouncedSearch(q, updateParams)

  const list = useLoader(
    (signal) => brandApi.list({ q, status, page, size: PAGE_SIZE, sort }, signal),
    JSON.stringify([q, status, sort, page]),
  )
  const filtersActive = Boolean(q || status)

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader
        title="Brands"
        description="Manufacturers and labels that products can be assigned to."
        actions={
          isAdmin && (
            <Link to="/brands/new" className={linkClass.primary}>
              Add brand
            </Link>
          )
        }
      />

      <Card>
        <form
          className="grid gap-4 md:grid-cols-[2fr_1fr_1fr]"
          role="search"
          aria-label="Filter brands"
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
            label="brands"
            filtersActive={filtersActive}
            onClearFilters={() => {
              setSearchInput('')
              updateParams({ q: null, status: null })
            }}
            emptyDescription={isAdmin ? 'Add a brand so products can be assigned to it.' : 'No brands have been created yet.'}
          >
            {(items) => (
              <div className="overflow-x-auto">
                <table className={table.table}>
                  <caption className="sr-only">Brands</caption>
                  <thead className={table.head}>
                    <tr>
                      <th scope="col" className={table.th}>Brand</th>
                      <th scope="col" className={table.th}>Website</th>
                      <th scope="col" className={`${table.th} text-right`}>Products</th>
                      <th scope="col" className={table.th}>Status</th>
                      {isAdmin && (
                        <th scope="col" className={`${table.th} text-right`}>
                          <span className="sr-only">Actions</span>
                        </th>
                      )}
                    </tr>
                  </thead>
                  <tbody className={table.body}>
                    {items.map((brand) => (
                      <tr key={brand.id} className={table.row}>
                        <td className={table.td}>
                          <Link to={`/brands/${brand.id}`} className={linkClass.text}>
                            {brand.name}
                          </Link>
                          <div className="font-mono text-xs text-slate-500">{brand.slug}</div>
                        </td>
                        <td className={table.td}>
                          {brand.websiteUrl ? (
                            <a
                              href={brand.websiteUrl}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="text-slate-600 underline-offset-2 hover:underline"
                            >
                              {hostOf(brand.websiteUrl)}
                            </a>
                          ) : (
                            <span className="text-slate-400">—</span>
                          )}
                        </td>
                        <td className={`${table.td} text-right tabular-nums`}>{brand.productCount}</td>
                        <td className={table.td}>
                          <StatusBadge status={brand.status} />
                        </td>
                        {isAdmin && (
                          <td className={`${table.td} whitespace-nowrap text-right`}>
                            <div className="flex justify-end gap-1">
                              <Link to={`/brands/${brand.id}/edit`} className={linkClass.row} aria-label={`Edit ${brand.name}`}>
                                Edit
                              </Link>
                              <StatusToggle
                                name={brand.name}
                                status={brand.status}
                                onChange={(next) => brandApi.setStatus(brand.id, next)}
                                onDone={list.reload}
                                deactivateHint={deactivateHints.brand}
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
