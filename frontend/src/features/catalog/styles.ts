const FOCUS = 'focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2'

export const linkClass = {
  primary: `inline-flex h-10 items-center rounded-md bg-indigo-600 px-4 text-sm font-medium text-white hover:bg-indigo-500 ${FOCUS}`,
  secondary: `inline-flex h-10 items-center rounded-md border border-slate-300 bg-white px-4 text-sm font-medium text-slate-700 hover:bg-slate-50 ${FOCUS}`,
  row: 'inline-flex h-8 items-center rounded-md px-3 text-xs font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500',
  text: 'font-medium text-indigo-600 hover:text-indigo-500 focus:outline-none focus-visible:underline',
}

export const table = {
  table: 'min-w-full divide-y divide-slate-200 text-sm',
  head: 'bg-slate-50 text-left text-xs font-semibold uppercase tracking-wide text-slate-500',
  th: 'px-3 py-3',
  td: 'px-3 py-3',
  body: 'divide-y divide-slate-100 bg-white',
  row: 'align-top hover:bg-slate-50',
}
