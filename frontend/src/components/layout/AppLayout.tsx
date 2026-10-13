import { useState } from 'react'
import { NavLink, Outlet, useMatch, useNavigate } from 'react-router'
import { useAuth } from '../../auth/authContext'
import { Badge } from '../ui/Badge'
import { Button } from '../ui/Button'

const NAV_ITEMS = [
  { to: '/products', label: 'Product Management', icon: '▦' },
  { to: '/chat', label: 'AI Chat', icon: '✦' },
]

export function AppLayout() {
  const { user, isAdmin, logout } = useAuth()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  // The chat manages its own scrolling areas, like a full-screen app.
  const fullHeight = useMatch('/chat') !== null

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-screen lg:flex">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-white focus:px-3 focus:py-2 focus:shadow"
      >
        Skip to content
      </a>

      <aside
        id="app-sidebar"
        className={`${menuOpen ? 'block' : 'hidden'} fixed inset-y-0 left-0 z-40 w-64 border-r border-slate-200 bg-slate-900 text-slate-100 lg:static lg:block`}
      >
        <div className="flex h-16 items-center gap-2 px-6 text-lg font-semibold">
          <img src="/favicon.svg" alt="" className="h-7 w-7" />
          Product RAG
        </div>
        <nav aria-label="Main navigation" className="space-y-1 px-3 py-4">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              onClick={() => setMenuOpen(false)}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400 ${
                  isActive ? 'bg-slate-800 text-white' : 'text-slate-300 hover:bg-slate-800 hover:text-white'
                }`
              }
            >
              <span aria-hidden="true" className="w-4 text-center">
                {item.icon}
              </span>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      {menuOpen && (
        <div className="fixed inset-0 z-30 bg-slate-900/40 lg:hidden" aria-hidden="true" onClick={() => setMenuOpen(false)} />
      )}

      <div className={`flex min-w-0 flex-1 flex-col ${fullHeight ? 'h-screen' : ''}`}>
        <header className="sticky top-0 z-20 flex h-16 items-center justify-between gap-4 border-b border-slate-200 bg-white px-4 sm:px-6">
          <div className="flex items-center gap-3">
            <button
              type="button"
              className="rounded-md p-2 text-slate-600 hover:bg-slate-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 lg:hidden"
              aria-label={menuOpen ? 'Close navigation menu' : 'Open navigation menu'}
              aria-expanded={menuOpen}
              aria-controls="app-sidebar"
              onClick={() => setMenuOpen((open) => !open)}
            >
              ☰
            </button>
            <span className="text-base font-semibold text-slate-900">Product RAG Admin Panel</span>
          </div>
          <div className="flex items-center gap-3">
            <div className="hidden text-right sm:block">
              <p className="text-sm font-medium text-slate-900">{user?.displayName}</p>
              <p className="text-xs text-slate-500">{user?.email}</p>
            </div>
            <Badge tone={isAdmin ? 'indigo' : 'gray'}>{user?.role}</Badge>
            <Button variant="secondary" size="sm" onClick={handleLogout}>
              Sign out
            </Button>
          </div>
        </header>

        <main
          id="main-content"
          className={fullHeight ? 'min-h-0 flex-1 overflow-hidden' : 'flex-1 px-4 py-6 sm:px-6 lg:px-8'}
        >
          <Outlet />
        </main>
      </div>
    </div>
  )
}
