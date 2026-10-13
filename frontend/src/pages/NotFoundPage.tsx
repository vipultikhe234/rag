import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-md py-16 text-center">
      <h1 className="text-2xl font-semibold text-slate-900">Page not found</h1>
      <p className="mt-2 text-sm text-slate-500">The page you are looking for does not exist.</p>
      <Link to="/products" className="mt-6 inline-block text-sm font-medium text-indigo-600 hover:text-indigo-500">
        Go to products
      </Link>
    </div>
  )
}
