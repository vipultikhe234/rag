import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { ArrowUpIcon, StopIcon } from '../../components/ui/icons'
import { MAX_QUESTION_LENGTH } from './chatTypes'

const MAX_HEIGHT_PX = 200

interface ComposerProps {
  streaming: boolean
  disabled?: boolean
  autoFocus?: boolean
  onSend: (question: string) => void
  onStop: () => void
}

export function Composer({ streaming, disabled = false, autoFocus = false, onSend, onStop }: ComposerProps) {
  const [value, setValue] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const trimmed = value.trim()
  const tooLong = value.length > MAX_QUESTION_LENGTH
  const canSend = trimmed.length > 0 && !tooLong && !disabled && !streaming

  const resize = (element: HTMLTextAreaElement) => {
    element.style.height = 'auto'
    element.style.height = `${Math.min(element.scrollHeight, MAX_HEIGHT_PX)}px`
  }

  const submit = () => {
    if (!canSend) return
    onSend(trimmed)
    setValue('')
    if (textareaRef.current) textareaRef.current.style.height = 'auto'
  }

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    submit()
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      submit()
    }
  }

  return (
    <form onSubmit={onSubmit} className="w-full">
      <label htmlFor="chat-question" className="sr-only">
        Ask about the product catalog
      </label>
      <div
        className={`flex items-end gap-2 rounded-[1.75rem] border bg-white py-2 pl-5 pr-2 shadow-[0_2px_12px_rgba(15,23,42,0.08)] transition focus-within:border-slate-300 ${
          tooLong ? 'border-red-300' : 'border-slate-200'
        }`}
      >
        <textarea
          id="chat-question"
          ref={textareaRef}
          rows={1}
          value={value}
          autoFocus={autoFocus}
          onChange={(event) => {
            setValue(event.target.value)
            resize(event.target)
          }}
          onKeyDown={onKeyDown}
          disabled={disabled}
          placeholder="Ask anything about your products"
          aria-describedby="chat-question-help"
          aria-invalid={tooLong || undefined}
          className="block max-h-[200px] min-h-[2.5rem] flex-1 resize-none bg-transparent py-2 text-[15px] leading-6 text-slate-900 placeholder:text-slate-400 focus:outline-none disabled:cursor-not-allowed"
        />
        {streaming ? (
          <button
            type="button"
            onClick={onStop}
            aria-label="Stop generating"
            title="Stop generating"
            className="mb-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-slate-900 text-white hover:bg-slate-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2"
          >
            <StopIcon className="h-4 w-4" />
          </button>
        ) : (
          <button
            type="submit"
            disabled={!canSend}
            aria-label="Send message"
            title="Send message"
            className="mb-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-slate-900 text-white hover:bg-slate-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400"
          >
            <ArrowUpIcon className="h-4 w-4" />
          </button>
        )}
      </div>
      <p id="chat-question-help" className="mt-2 text-center text-xs text-slate-400">
        {tooLong ? (
          <span className="text-red-600">
            Your question is too long ({value.length}/{MAX_QUESTION_LENGTH} characters).
          </span>
        ) : (
          'Answers use only your product catalog. Prices and stock come straight from the database.'
        )}
      </p>
    </form>
  )
}
