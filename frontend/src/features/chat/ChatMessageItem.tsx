import { useState } from 'react'
import { CheckIcon, ChevronDownIcon, CopyIcon, RetryIcon, SparkleIcon } from '../../components/ui/icons'
import type { DisplayMessage } from './chatTypes'
import { Markdown } from './Markdown'
import { SourceCard } from './SourceCard'

const STRATEGY_LABELS: Record<string, string> = {
  EXACT_SKU: 'Exact SKU lookup',
  STRUCTURED: 'Database filter',
  HYBRID: 'Database filter + semantic search',
  SEMANTIC: 'Semantic search',
  CLARIFICATION: 'Clarification needed',
  CATALOG: 'Catalog totals from the database',
  DISCOVERY: 'Brand / category / attribute / variant search',
}

interface ChatMessageItemProps {
  message: DisplayMessage
  onRetry?: (question: string) => void
}

export function ChatMessageItem({ message, onRetry }: ChatMessageItemProps) {
  if (message.role === 'USER') {
    return (
      <div className="flex justify-end">
        <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-3xl bg-slate-100 px-5 py-2.5 text-[15px] leading-7 text-slate-900">
          <span className="sr-only">You said: </span>
          {message.content}
        </div>
      </div>
    )
  }
  return <AssistantMessage message={message} onRetry={onRetry} />
}

function AssistantMessage({ message, onRetry }: ChatMessageItemProps) {
  const { status, content } = message
  const pending = status === 'searching' || (status === 'thinking' && !content)
  const showActions = (status === 'done' || status === 'stopped') && content.length > 0

  return (
    <div className="flex gap-4">
      <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-slate-200 bg-white text-indigo-600">
        <SparkleIcon className="h-4 w-4" />
      </div>
      <div className="min-w-0 flex-1 pt-0.5">
        <span className="sr-only">Assistant: </span>
        {pending && <TypingIndicator label={status === 'searching' ? 'Searching the catalog' : 'Thinking'} />}

        {content && (
          <div className="break-words text-[15px] leading-7 text-slate-800">
            <Markdown text={status === 'streaming' ? `${content} ▍` : content} />
          </div>
        )}

        {status === 'stopped' && (
          <p className="mt-2 text-sm italic text-slate-400">
            {content ? 'Stopped generating.' : 'Stopped before an answer was generated.'}
          </p>
        )}

        {status === 'failed' && (
          <div
            role="alert"
            className="mt-2 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800"
          >
            <span>{message.error ?? 'Something went wrong.'} Your question was not saved.</span>
            {onRetry && message.question && (
              <button
                type="button"
                onClick={() => onRetry(message.question ?? '')}
                className="inline-flex items-center gap-1.5 rounded-full border border-red-300 bg-white px-3 py-1 font-medium text-red-700 hover:bg-red-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
              >
                <RetryIcon className="h-3.5 w-3.5" /> Retry
              </button>
            )}
          </div>
        )}

        {(message.sources.length > 0 || showActions) && (
          <div className="mt-2 flex flex-wrap items-center gap-1">
            {showActions && <CopyButton text={content} />}
            {message.sources.length > 0 && <SourcesToggle message={message} />}
          </div>
        )}
      </div>
    </div>
  )
}

function TypingIndicator({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-3 py-1 text-[15px] text-slate-500" role="status">
      <span className="flex gap-1" aria-hidden="true">
        {[0, 1, 2].map((index) => (
          <span
            key={index}
            className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400"
            style={{ animationDelay: `${index * 150}ms` }}
          />
        ))}
      </span>
      {label}…
    </div>
  )
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  const copy = () => {
    void navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    })
  }
  return (
    <button
      type="button"
      onClick={copy}
      title={copied ? 'Copied' : 'Copy'}
      aria-label={copied ? 'Copied' : 'Copy answer'}
      className="rounded-lg p-1.5 text-slate-500 hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
    >
      {copied ? <CheckIcon className="h-4 w-4" /> : <CopyIcon className="h-4 w-4" />}
    </button>
  )
}

function SourcesToggle({ message }: { message: DisplayMessage }) {
  const [open, setOpen] = useState(false)
  const count = message.sources.length
  const strategy = message.retrieval ? (STRATEGY_LABELS[message.retrieval.strategy] ?? message.retrieval.strategy) : null
  const filters = message.retrieval?.appliedFilters.filter((filter) => filter !== 'status = ACTIVE') ?? []
  const panelId = `sources-${message.id}`

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-controls={panelId}
        className="inline-flex items-center gap-1.5 rounded-full border border-slate-200 px-3 py-1 text-xs font-medium text-slate-600 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
      >
        {count} {count === 1 ? 'source' : 'sources'}
        {strategy && <span className="font-normal text-slate-400">· {strategy}</span>}
        <ChevronDownIcon className={`h-3.5 w-3.5 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <section id={panelId} aria-label="Source products" className="mt-2 w-full space-y-2">
          {(filters.length > 0 || message.retrieval?.totalMatches != null) && (
            <p className="text-xs text-slate-500">
              {filters.length > 0 && <>Filters: {filters.join(', ')}</>}
              {filters.length > 0 && message.retrieval?.totalMatches != null && ' · '}
              {message.retrieval?.totalMatches != null && <>{message.retrieval.totalMatches} matching product(s)</>}
            </p>
          )}
          <div className="grid gap-2 sm:grid-cols-2">
            {message.sources.map((source) => (
              <SourceCard key={`${message.id}-${source.productId}`} source={source} />
            ))}
          </div>
        </section>
      )}
    </>
  )
}
