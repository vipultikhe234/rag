const currencyFormatters = new Map<string, Intl.NumberFormat>()

export function formatPrice(amount: number, currency: string): string {
  let formatter = currencyFormatters.get(currency)
  if (!formatter) {
    try {
      formatter = new Intl.NumberFormat(currency === 'INR' ? 'en-IN' : undefined, {
        style: 'currency',
        currency,
      })
    } catch {
      return `${currency} ${amount.toFixed(2)}`
    }
    currencyFormatters.set(currency, formatter)
  }
  return formatter.format(amount)
}

const dateTimeFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : dateTimeFormatter.format(date)
}

export function formatRelevance(score: number | null): string | null {
  if (score === null || score === undefined) return null
  return `${Math.round(score * 100)}%`
}

export function humanize(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((part, index) => (index === 0 ? part.charAt(0).toUpperCase() + part.slice(1) : part))
    .join(' ')
}
