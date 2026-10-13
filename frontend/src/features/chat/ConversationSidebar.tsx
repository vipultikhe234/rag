import { useMemo, useState, type KeyboardEvent } from 'react'
import type { ConversationSummary } from '../../api/types'
import { NewChatIcon, PencilIcon, SearchIcon, SidebarIcon, TrashIcon } from '../../components/ui/icons'

const DAY_MS = 24 * 60 * 60 * 1000

interface ConversationSidebarProps {
  conversations: ConversationSummary[]
  /** Reference time for the date groups (when the list was loaded). */
  now: number
  loading: boolean
  activeId: string | null
  onSelect: (id: string) => void
  onNewChat: () => void
  onRename: (id: string, title: string) => Promise<void>
  onDelete: (conversation: ConversationSummary) => void
  onCollapse: () => void
}

export function ConversationSidebar({
  conversations,
  now,
  loading,
  activeId,
  onSelect,
  onNewChat,
  onRename,
  onDelete,
  onCollapse,
}: ConversationSidebarProps) {
  const [filter, setFilter] = useState('')
  const groups = useMemo(() => {
    const needle = filter.trim().toLowerCase()
    const visible = needle
      ? conversations.filter((conversation) => conversation.title.toLowerCase().includes(needle))
      : conversations
    return groupByDate(visible, now)
  }, [conversations, filter, now])

  return (
    <div className="flex h-full flex-col bg-slate-50">
      <div className="flex items-center justify-between px-3 pb-1 pt-3">
        <button
          type="button"
          onClick={onCollapse}
          aria-label="Close sidebar"
          title="Close sidebar"
          className="rounded-lg p-2 text-slate-500 hover:bg-slate-200/70 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
        >
          <SidebarIcon />
        </button>
        <button
          type="button"
          onClick={onNewChat}
          aria-label="New chat"
          title="New chat"
          className="rounded-lg p-2 text-slate-500 hover:bg-slate-200/70 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
        >
          <NewChatIcon />
        </button>
      </div>

      <div className="px-3 pb-2">
        <button
          type="button"
          onClick={onNewChat}
          className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium text-slate-800 hover:bg-slate-200/70 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
        >
          <NewChatIcon className="h-4 w-4" /> New chat
        </button>
        <label className="mt-1 flex items-center gap-2 rounded-lg px-3 py-2 text-sm text-slate-500 focus-within:bg-white focus-within:ring-1 focus-within:ring-slate-300 hover:bg-slate-200/70">
          <SearchIcon className="h-4 w-4 shrink-0" />
          <span className="sr-only">Search chats</span>
          <input
            type="search"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            placeholder="Search chats"
            className="w-full bg-transparent text-slate-800 placeholder:text-slate-500 focus:outline-none"
          />
        </label>
      </div>

      <nav aria-label="Chat history" className="scrollbar-none flex-1 overflow-y-auto px-3 pb-4">
        {loading ? (
          <ul className="space-y-2 px-3 pt-4" aria-label="Loading chats">
            {[0, 1, 2, 3, 4].map((index) => (
              <li key={index} className="h-4 animate-pulse rounded bg-slate-200" style={{ width: `${85 - index * 9}%` }} />
            ))}
          </ul>
        ) : groups.length === 0 ? (
          <p className="px-3 pt-4 text-sm text-slate-500">{filter ? 'No chats match your search.' : 'No chats yet.'}</p>
        ) : (
          groups.map((group) => (
            <section key={group.label} className="mt-4">
              <h2 className="px-3 pb-1 text-xs font-semibold text-slate-500">{group.label}</h2>
              <ul>
                {group.items.map((conversation) => (
                  <ConversationItem
                    key={conversation.id}
                    conversation={conversation}
                    active={conversation.id === activeId}
                    onSelect={onSelect}
                    onRename={onRename}
                    onDelete={onDelete}
                  />
                ))}
              </ul>
            </section>
          ))
        )}
      </nav>
    </div>
  )
}

interface ConversationItemProps {
  conversation: ConversationSummary
  active: boolean
  onSelect: (id: string) => void
  onRename: (id: string, title: string) => Promise<void>
  onDelete: (conversation: ConversationSummary) => void
}

function ConversationItem({ conversation, active, onSelect, onRename, onDelete }: ConversationItemProps) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(conversation.title)

  const startEditing = () => {
    setDraft(conversation.title)
    setEditing(true)
  }

  const commit = () => {
    const title = draft.trim()
    setEditing(false)
    if (title && title !== conversation.title) void onRename(conversation.id, title)
  }

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter') commit()
    if (event.key === 'Escape') setEditing(false)
  }

  if (editing) {
    return (
      <li className="py-0.5">
        <input
          autoFocus
          value={draft}
          maxLength={200}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={commit}
          onKeyDown={onKeyDown}
          aria-label="Chat title"
          className="w-full rounded-lg border border-indigo-400 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
      </li>
    )
  }

  return (
    <li className="group relative py-0.5">
      <button
        type="button"
        onClick={() => onSelect(conversation.id)}
        aria-current={active ? 'page' : undefined}
        title={conversation.title}
        className={`block w-full truncate rounded-lg py-2 pl-3 pr-16 text-left text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 ${
          active ? 'bg-slate-200 font-medium text-slate-900' : 'text-slate-700 hover:bg-slate-200/70'
        }`}
      >
        {conversation.title}
      </button>
      <div
        className={`absolute inset-y-0.5 right-1 flex items-center gap-0.5 ${
          active ? 'flex' : 'hidden group-focus-within:flex group-hover:flex'
        }`}
      >
        <button
          type="button"
          onClick={startEditing}
          aria-label={`Rename chat “${conversation.title}”`}
          title="Rename"
          className="rounded-md p-1.5 text-slate-500 hover:bg-white hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
        >
          <PencilIcon className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          onClick={() => onDelete(conversation)}
          aria-label={`Delete chat “${conversation.title}”`}
          title="Delete"
          className="rounded-md p-1.5 text-slate-500 hover:bg-white hover:text-red-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
        >
          <TrashIcon className="h-3.5 w-3.5" />
        </button>
      </div>
    </li>
  )
}

interface DateGroup {
  label: string
  items: ConversationSummary[]
}

function groupByDate(conversations: ConversationSummary[], now: number): DateGroup[] {
  const startOfToday = new Date(now)
  startOfToday.setHours(0, 0, 0, 0)
  const today = startOfToday.getTime()
  const buckets: Array<{ label: string; from: number }> = [
    { label: 'Today', from: today },
    { label: 'Yesterday', from: today - DAY_MS },
    { label: 'Previous 7 days', from: today - 7 * DAY_MS },
    { label: 'Previous 30 days', from: today - 30 * DAY_MS },
    { label: 'Older', from: Number.NEGATIVE_INFINITY },
  ]
  const groups: DateGroup[] = buckets.map((bucket) => ({ label: bucket.label, items: [] }))
  for (const conversation of conversations) {
    const time = Date.parse(conversation.updatedAt)
    const index = buckets.findIndex((bucket) => time >= bucket.from)
    groups[index === -1 ? groups.length - 1 : index].items.push(conversation)
  }
  return groups.filter((group) => group.items.length > 0)
}
