import type { SourceReference } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { formatPrice, formatRelevance } from '../../lib/format'

const MATCH_LABELS: Record<SourceReference['matchType'], string> = {
  EXACT_SKU: 'Exact SKU',
  NAME_MATCH: 'Name match',
  STRUCTURED_FILTER: 'Filter match',
  SEMANTIC: 'Semantic match',
}

export function SourceCard({ source }: { source: SourceReference }) {
  const relevance = formatRelevance(source.relevanceScore)
  return (
    <article className="flex min-w-0 flex-col gap-1 rounded-md border border-slate-200 bg-white p-3 text-sm shadow-sm">
      <div className="flex items-start justify-between gap-2">
        <h4 className="min-w-0 truncate font-medium text-slate-900" title={source.name}>
          {source.name}
        </h4>
        {relevance && (
          <Badge tone="indigo" title="Semantic similarity between your question and this product">
            {relevance}
          </Badge>
        )}
      </div>
      <p className="font-mono text-xs text-slate-500">{source.sku}</p>
      <p className="text-xs text-slate-600">
        {source.category}
        {source.brand ? ` · ${source.brand}` : ''}
      </p>
      <div className="mt-1 flex flex-wrap items-center gap-2">
        <span className="font-semibold tabular-nums text-slate-900">{formatPrice(source.price, source.currency)}</span>
        <Badge tone={source.stockQuantity > 0 ? 'green' : 'red'}>
          {source.stockQuantity > 0 ? `${source.stockQuantity} in stock` : 'Out of stock'}
        </Badge>
        <Badge tone="gray">{MATCH_LABELS[source.matchType] ?? source.matchType}</Badge>
      </div>
    </article>
  )
}
