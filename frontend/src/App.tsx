import { lazy, Suspense, type ComponentType, type ReactNode } from 'react'
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

// The catalog admin pages share one lazily loaded chunk for the same reason.
type CatalogPages = typeof import('./pages/catalog')
type FormProps = { mode: 'create' | 'edit' }
const catalogPage = <P extends object>(name: keyof CatalogPages) =>
  lazy(() =>
    import('./pages/catalog').then((module) => ({ default: module[name] as unknown as ComponentType<P> })),
  )

interface CatalogModule {
  path: string
  List: ComponentType
  Details: ComponentType
  Form: ComponentType<FormProps>
}

const CATALOG_MODULES: CatalogModule[] = [
  {
    path: '/categories',
    List: catalogPage('CategoryListPage'),
    Details: catalogPage('CategoryDetailsPage'),
    Form: catalogPage<FormProps>('CategoryFormPage'),
  },
  {
    path: '/brands',
    List: catalogPage('BrandListPage'),
    Details: catalogPage('BrandDetailsPage'),
    Form: catalogPage<FormProps>('BrandFormPage'),
  },
  {
    path: '/product-attributes',
    List: catalogPage('AttributeListPage'),
    Details: catalogPage('AttributeDetailsPage'),
    Form: catalogPage<FormProps>('AttributeFormPage'),
  },
  {
    path: '/product-variants',
    List: catalogPage('VariantListPage'),
    Details: catalogPage('VariantDetailsPage'),
    Form: catalogPage<FormProps>('VariantFormPage'),
  },
]

function CatalogSuspense({ children }: { children: ReactNode }) {
  return (
    <PageErrorBoundary>
      <Suspense fallback={<LoadingBlock label="Loading page" />}>{children}</Suspense>
    </PageErrorBoundary>
  )
}

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
        {CATALOG_MODULES.map(({ path, List, Details, Form }) => [
          <Route
            key={path}
            path={path}
            element={
              <CatalogSuspense>
                <List />
              </CatalogSuspense>
            }
          />,
          <Route
            key={`${path}/new`}
            path={`${path}/new`}
            element={
              <ProtectedRoute requireAdmin>
                <CatalogSuspense>
                  <Form mode="create" />
                </CatalogSuspense>
              </ProtectedRoute>
            }
          />,
          <Route
            key={`${path}/:id`}
            path={`${path}/:id`}
            element={
              <CatalogSuspense>
                <Details />
              </CatalogSuspense>
            }
          />,
          <Route
            key={`${path}/:id/edit`}
            path={`${path}/:id/edit`}
            element={
              <ProtectedRoute requireAdmin>
                <CatalogSuspense>
                  <Form mode="edit" />
                </CatalogSuspense>
              </ProtectedRoute>
            }
          />,
        ])}
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
