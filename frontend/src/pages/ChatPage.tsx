import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { streamChat } from '../api/chatStream'
import { chatApi } from '../api/endpoints'
import { normalizeError } from '../api/errors'
import type { ConversationSummary } from '../api/types'
import { useToast } from '../components/toast/toastContext'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { ArrowDownIcon, NewChatIcon, SidebarIcon, SparkleIcon } from '../components/ui/icons'
import { LoadingBlock } from '../components/ui/Spinner'
import { ErrorState } from '../components/ui/States'
import { ChatMessageItem } from '../features/chat/ChatMessageItem'
import { fromServer, type DisplayMessage } from '../features/chat/chatTypes'
import { Composer } from '../features/chat/Composer'
import { ConversationSidebar } from '../features/chat/ConversationSidebar'

const SUGGESTIONS = [
  { title: 'Travel essentials', prompt: 'Which products are good for travel?' },
  { title: 'Budget picks', prompt: 'Which products cost less than ₹1,000?' },
  { title: 'Well stocked', prompt: 'Which products have stock greater than 10?' },
  { title: 'Exact lookup', prompt: 'What is the price of SKU WH-001?' },
]

/** Stop button vs. leaving the conversation: only a user stop should reset an unsaved new chat. */
type AbortReason = 'stop' | 'leave'

interface Thread {
  key: string | null
  messages: DisplayMessage[]
}

/** Mutable progress of one streamed answer, shared by its callbacks. */
class StreamProgress {
  key: string | null
  received = ''

  constructor(key: string | null) {
    this.key = key
  }

  assignConversation(id: string) {
    this.key = id
  }

  append(text: string) {
    this.received += text
  }
}

interface ConversationList {
  items: ConversationSummary[]
  loadedAt: number
}

export function ChatPage() {
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const conversationId = params.get('c')

  const [conversations, setConversations] = useState<ConversationList>({ items: [], loadedAt: 0 })
  const [conversationsLoading, setConversationsLoading] = useState(true)
  const [conversationsVersion, setConversationsVersion] = useState(0)
  // Messages are tagged with the conversation they belong to, so switching conversations derives an empty
  // or loading view instead of resetting state inside an effect.
  const [thread, setThread] = useState<Thread>({ key: null, messages: [] })
  const [loadError, setLoadError] = useState<{ key: string; message: string } | null>(null)
  const [streaming, setStreaming] = useState(false)
  const [sidebarOpen, setSidebarOpen] = useState(true)
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false)
  const [pendingDelete, setPendingDelete] = useState<ConversationSummary | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [showJump, setShowJump] = useState(false)

  const abortRef = useRef<AbortController | null>(null)
  const localIds = useRef(0)
  const scrollRef = useRef<HTMLDivElement>(null)
  const stickToBottom = useRef(true)

  const messages = useMemo(
    () => (thread.key === conversationId ? thread.messages : []),
    [thread, conversationId],
  )
  const messagesError = conversationId && loadError?.key === conversationId ? loadError.message : null
  const messagesLoading = conversationId !== null && thread.key !== conversationId && messagesError === null
  const activeTitle = conversations.items.find((conversation) => conversation.id === conversationId)?.title

  const refreshConversations = useCallback(() => setConversationsVersion((version) => version + 1), [])

  useEffect(() => {
    let cancelled = false
    chatApi
      .conversations()
      .then((page) => {
        if (!cancelled) setConversations({ items: page.content, loadedAt: Date.now() })
      })
      .catch((err: unknown) => {
        if (!cancelled) toast.error(normalizeError(err).message)
      })
      .finally(() => {
        if (!cancelled) setConversationsLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [conversationsVersion, toast])

  useEffect(() => {
    if (!conversationId || thread.key === conversationId || loadError?.key === conversationId) return
    const controller = new AbortController()
    chatApi
      .messages(conversationId, controller.signal)
      .then((loaded) => setThread({ key: conversationId, messages: loaded.map(fromServer) }))
      .catch((err: unknown) => {
        if (controller.signal.aborted) return
        const normalized = normalizeError(err)
        setLoadError({
          key: conversationId,
          message:
            normalized.code === 'CONVERSATION_NOT_FOUND' ? 'This conversation no longer exists.' : normalized.message,
        })
      })
    return () => controller.abort()
  }, [conversationId, thread.key, loadError])

  useEffect(() => {
    const element = scrollRef.current
    if (element && stickToBottom.current) element.scrollTop = element.scrollHeight
  }, [messages])

  useEffect(() => () => abortRef.current?.abort('leave' satisfies AbortReason), [])

  const onScroll = () => {
    const element = scrollRef.current
    if (!element) return
    const distance = element.scrollHeight - element.scrollTop - element.clientHeight
    stickToBottom.current = distance < 80
    setShowJump(distance > 240)
  }

  const jumpToBottom = () => {
    const element = scrollRef.current
    if (!element) return
    stickToBottom.current = true
    element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' })
  }

  const leaveCurrentStream = () => {
    if (!abortRef.current) return
    abortRef.current.abort('leave' satisfies AbortReason)
    abortRef.current = null
    setStreaming(false)
    // A partial answer is stored by the server; show it in the list shortly.
    window.setTimeout(refreshConversations, 800)
  }

  const openConversation = (id: string | null) => {
    if (id !== conversationId || abortRef.current) leaveCurrentStream()
    if (id === null) setThread({ key: null, messages: [] })
    stickToBottom.current = true
    setShowJump(false)
    setMobileSidebarOpen(false)
    setParams(id ? { c: id } : {})
  }

  const send = (question: string) => {
    if (abortRef.current) return
    localIds.current += 1
    const userId = `local-user-${localIds.current}`
    const assistantId = `local-assistant-${localIds.current}`
    const createdAt = new Date().toISOString()
    const stream = new StreamProgress(conversationId)
    const isNew = conversationId === null

    const patch = (ids: string[], change: (message: DisplayMessage) => DisplayMessage) =>
      setThread((current) =>
        current.key !== stream.key
          ? current
          : { ...current, messages: current.messages.map((m) => (ids.includes(m.id) ? change(m) : m)) },
      )

    setThread((current) => ({
      key: stream.key,
      messages: [
        ...(current.key === stream.key ? current.messages.filter((message) => !message.discard) : []),
        { id: userId, role: 'USER', content: question, sources: [], retrieval: null, createdAt, status: 'done' },
        {
          id: assistantId,
          role: 'ASSISTANT',
          content: '',
          sources: [],
          retrieval: null,
          createdAt,
          status: 'searching',
          question,
        },
      ],
    }))
    stickToBottom.current = true
    const controller = new AbortController()
    abortRef.current = controller
    setStreaming(true)

    streamChat(question, stream.key, {
      signal: controller.signal,
      onMeta: (meta) => {
        if (isNew) {
          stream.assignConversation(meta.conversationId)
          setThread((current) => (current.key === null ? { ...current, key: meta.conversationId } : current))
          setConversations((current) => ({
            ...current,
            items: [
              { id: meta.conversationId, title: question, createdAt, updatedAt: createdAt },
              ...current.items.filter((item) => item.id !== meta.conversationId),
            ],
          }))
          setParams({ c: meta.conversationId }, { replace: true })
        }
        patch([assistantId], (m) => ({ ...m, sources: meta.sources, retrieval: meta.retrieval, status: 'thinking' }))
      },
      onDelta: (text) => {
        stream.append(text)
        patch([assistantId], (m) => ({ ...m, content: m.content + text, status: 'streaming' }))
      },
    })
      .then((answer) => {
        patch([assistantId], (m) => ({
          ...m,
          id: answer.messageId,
          content: answer.answer,
          sources: answer.sources,
          retrieval: answer.retrieval,
          createdAt: answer.createdAt,
          status: 'done',
        }))
        refreshConversations()
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) {
          if (controller.signal.reason !== ('stop' satisfies AbortReason)) return
          if (stream.received) {
            patch([assistantId], (m) => ({ ...m, status: 'stopped' }))
            window.setTimeout(refreshConversations, 800)
          } else {
            patch([userId, assistantId], (m) => ({ ...m, status: m.role === 'USER' ? 'done' : 'stopped', discard: true }))
            if (isNew && stream.key) forgetUnsavedConversation(stream.key)
          }
          return
        }
        const normalized = normalizeError(err)
        if (normalized.code === 'CONVERSATION_NOT_FOUND') {
          toast.error('This conversation no longer exists.')
          setThread({ key: null, messages: [] })
          setParams({}, { replace: true })
          refreshConversations()
          return
        }
        patch([userId, assistantId], (m) => ({
          ...m,
          status: m.role === 'USER' ? 'done' : 'failed',
          error: m.role === 'USER' ? undefined : normalized.message,
          discard: true,
        }))
        if (isNew && stream.key) forgetUnsavedConversation(stream.key)
      })
      .finally(() => {
        if (abortRef.current === controller) {
          abortRef.current = null
          setStreaming(false)
        }
      })
  }

  /** A new chat whose first exchange was not stored must not keep pointing at a non-existent conversation. */
  const forgetUnsavedConversation = (id: string) => {
    setThread((current) => (current.key === id ? { ...current, key: null } : current))
    setConversations((current) => ({ ...current, items: current.items.filter((item) => item.id !== id) }))
    setParams({}, { replace: true })
  }

  const stop = () => abortRef.current?.abort('stop' satisfies AbortReason)

  const renameConversation = async (id: string, title: string) => {
    try {
      const updated = await chatApi.renameConversation(id, title)
      setConversations((current) => ({
        ...current,
        items: current.items.map((item) => (item.id === id ? { ...item, title: updated.title } : item)),
      }))
    } catch (err) {
      toast.error(normalizeError(err).message)
    }
  }

  const deleteConversation = async () => {
    if (!pendingDelete) return
    setDeleting(true)
    try {
      await chatApi.deleteConversation(pendingDelete.id)
      setConversations((current) => ({
        ...current,
        items: current.items.filter((item) => item.id !== pendingDelete.id),
      }))
      if (pendingDelete.id === conversationId) openConversation(null)
      setPendingDelete(null)
      toast.success('Chat deleted.')
    } catch (err) {
      toast.error(normalizeError(err).message)
    } finally {
      setDeleting(false)
    }
  }

  const sidebar = (onCollapse: () => void) => (
    <ConversationSidebar
      conversations={conversations.items}
      now={conversations.loadedAt}
      loading={conversationsLoading}
      activeId={conversationId}
      onSelect={(id) => openConversation(id)}
      onNewChat={() => openConversation(null)}
      onRename={renameConversation}
      onDelete={setPendingDelete}
      onCollapse={onCollapse}
    />
  )

  const isEmpty = !conversationId && messages.length === 0

  return (
    <div className="flex h-full min-h-0">
      {sidebarOpen && (
        <aside className="hidden w-64 shrink-0 border-r border-slate-200 md:block" aria-label="Chats">
          {sidebar(() => setSidebarOpen(false))}
        </aside>
      )}
      {mobileSidebarOpen && (
        <div className="fixed inset-0 z-40 md:hidden">
          <div className="absolute inset-0 bg-slate-900/40" aria-hidden="true" onClick={() => setMobileSidebarOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-72 max-w-[85vw] shadow-xl" aria-label="Chats">
            {sidebar(() => setMobileSidebarOpen(false))}
          </aside>
        </div>
      )}

      <section className="relative flex min-w-0 flex-1 flex-col bg-white" aria-label="Chat">
        <header className="flex h-14 shrink-0 items-center gap-1 px-3">
          <button
            type="button"
            onClick={() => setMobileSidebarOpen(true)}
            aria-label="Open chat history"
            className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 md:hidden"
          >
            <SidebarIcon />
          </button>
          {!sidebarOpen && (
            <>
              <button
                type="button"
                onClick={() => setSidebarOpen(true)}
                aria-label="Open sidebar"
                title="Open sidebar"
                className="hidden rounded-lg p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 md:block"
              >
                <SidebarIcon />
              </button>
              <button
                type="button"
                onClick={() => openConversation(null)}
                aria-label="New chat"
                title="New chat"
                className="hidden rounded-lg p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 md:block"
              >
                <NewChatIcon />
              </button>
            </>
          )}
          <h1 className="ml-1 min-w-0 truncate text-base font-semibold text-slate-800">
            {conversationId ? (activeTitle ?? 'Chat') : 'Product Assistant'}
          </h1>
          <button
            type="button"
            onClick={() => openConversation(null)}
            aria-label="New chat"
            className="ml-auto rounded-lg p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 md:hidden"
          >
            <NewChatIcon />
          </button>
        </header>

        {isEmpty ? (
          <div className="flex flex-1 flex-col items-center justify-center overflow-y-auto px-4 pb-16">
            <div className="w-full max-w-3xl">
              <div className="mb-8 flex flex-col items-center text-center">
                <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full border border-slate-200 text-indigo-600">
                  <SparkleIcon className="h-6 w-6" />
                </div>
                <h2 className="text-2xl font-semibold text-slate-900 sm:text-3xl">What are you looking for today?</h2>
                <p className="mt-2 text-sm text-slate-500">Ask about products, prices, stock or features in your catalog.</p>
              </div>
              <Composer streaming={streaming} autoFocus onSend={send} onStop={stop} />
              <ul className="mt-6 grid gap-3 sm:grid-cols-2">
                {SUGGESTIONS.map((suggestion) => (
                  <li key={suggestion.prompt}>
                    <button
                      type="button"
                      onClick={() => send(suggestion.prompt)}
                      disabled={streaming}
                      className="w-full rounded-2xl border border-slate-200 px-4 py-3 text-left hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:opacity-50"
                    >
                      <span className="block text-sm font-medium text-slate-800">{suggestion.title}</span>
                      <span className="block truncate text-sm text-slate-500">{suggestion.prompt}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        ) : (
          <>
            <div
              ref={scrollRef}
              onScroll={onScroll}
              className="flex-1 overflow-y-auto"
              aria-live="polite"
              aria-busy={streaming || messagesLoading}
            >
              <div className="mx-auto w-full max-w-3xl space-y-8 px-4 pb-10 pt-4">
                {messagesLoading ? (
                  <LoadingBlock label="Loading messages" />
                ) : messagesError ? (
                  <ErrorState message={messagesError} onRetry={() => openConversation(null)} />
                ) : (
                  messages.map((message) => (
                    <ChatMessageItem key={message.id} message={message} onRetry={streaming ? undefined : send} />
                  ))
                )}
              </div>
            </div>

            {showJump && (
              <button
                type="button"
                onClick={jumpToBottom}
                aria-label="Scroll to latest message"
                className="absolute bottom-36 left-1/2 flex h-9 w-9 -translate-x-1/2 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-600 shadow-md hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
              >
                <ArrowDownIcon className="h-4 w-4" />
              </button>
            )}

            <div className="shrink-0 px-4 pb-4 pt-2">
              <div className="mx-auto w-full max-w-3xl">
                <Composer
                  streaming={streaming}
                  disabled={messagesLoading || messagesError !== null}
                  autoFocus
                  onSend={send}
                  onStop={stop}
                />
              </div>
            </div>
          </>
        )}
      </section>

      <ConfirmDialog
        open={pendingDelete !== null}
        title="Delete chat?"
        confirmLabel="Delete"
        busy={deleting}
        onConfirm={() => void deleteConversation()}
        onCancel={() => setPendingDelete(null)}
      >
        This will delete <strong>{pendingDelete?.title}</strong> and all of its messages.
      </ConfirmDialog>
    </div>
  )
}
