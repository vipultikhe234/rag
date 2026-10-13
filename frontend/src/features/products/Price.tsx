import { formatPrice } from '../../lib/format'

/** Price with the compare-at (original) price struck through when the item is on sale. */
export function SalePrice({
  price,
  compareAtPrice,
  currency,
}: {
  price: number
  compareAtPrice: number | null
  currency: string
}) {
  return (
    <span className="tabular-nums">
      {formatPrice(price, currency)}
      {compareAtPrice !== null && (
        <span className="ml-2 text-xs text-slate-500 line-through">{formatPrice(compareAtPrice, currency)}</span>
      )}
    </span>
  )
}

/** "₹69,999" or "₹69,999 – ₹89,999" for a price range. */
export function PriceRange({ min, max, currency }: { min: number | null; max: number | null; currency: string }) {
  if (min === null) return <span className="text-slate-400">—</span>
  if (max === null || max === min) return <span className="tabular-nums">{formatPrice(min, currency)}</span>
  return (
    <span className="tabular-nums">
      {formatPrice(min, currency)} – {formatPrice(max, currency)}
    </span>
  )
}
