import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { AppLayout } from './components/layout/AppLayout'
import { PageErrorBoundary } from './components/ui/PageErrorBoundary'
import { LoadingBlock } from './components/ui/Spinner'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { ProductFormPage } from './pages/ProductFormPage'
import { ProductListPage } from './pages/ProductListPage'

// The chat (with its Markdown renderer) is loaded on first visit to keep the initial bundle small.
const ChatPage = lazy(() => import('./pages/ChatPage').then((module) => ({ default: module.ChatPage })))

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="/products" replace />} />
        <Route path="/products" element={<ProductListPage />} />
        <Route
          path="/products/new"
          element={
            <ProtectedRoute requireAdmin>
              <ProductFormPage mode="create" />
            </ProtectedRoute>
          }
        />
        <Route
          path="/products/:id/edit"
          element={
            <ProtectedRoute requireAdmin>
              <ProductFormPage mode="edit" />
            </ProtectedRoute>
          }
        />
        <Route
          path="/chat"
          element={
            <PageErrorBoundary>
              <Suspense fallback={<LoadingBlock label="Loading chat" />}>
                <ChatPage />
              </Suspense>
            </PageErrorBoundary>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
