import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { z } from 'zod'
import { normalizeError } from '../api/errors'
import { useAuth } from '../auth/authContext'
import { Button } from '../components/ui/Button'
import { TextField } from '../components/ui/FormField'
import { InlineAlert } from '../components/ui/States'

const loginSchema = z.object({
  email: z
    .string()
    .trim()
    .min(1, 'Email is required')
    .max(254, 'Email is too long')
    .pipe(z.email('Enter a valid email address')),
  password: z.string().min(1, 'Password is required').max(128),
})

type LoginForm = z.infer<typeof loginSchema>

export function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [serverError, setServerError] = useState<string | null>(null)
  const from = (location.state as { from?: string } | null)?.from ?? '/products'

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } })

  if (user) {
    return <Navigate to={from} replace />
  }

  const onSubmit = async (values: LoginForm) => {
    setServerError(null)
    try {
      await login(values.email, values.password)
      navigate(from, { replace: true })
    } catch (error) {
      const normalized = normalizeError(error)
      setServerError(normalized.status === 401 ? 'Invalid email or password.' : normalized.message)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100 px-4">
      <div className="w-full max-w-sm space-y-6 rounded-xl border border-slate-200 bg-white p-8 shadow-sm">
        <div className="space-y-1 text-center">
          <img src="/favicon.svg" alt="" className="mx-auto h-10 w-10" />
          <h1 className="text-xl font-semibold text-slate-900">Product RAG Admin Panel</h1>
          <p className="text-sm text-slate-500">Sign in to manage products and chat with the catalog.</p>
        </div>

        {serverError && <InlineAlert tone="red">{serverError}</InlineAlert>}

        <form className="space-y-4" onSubmit={handleSubmit(onSubmit)} noValidate>
          <TextField
            label="Email"
            type="email"
            autoComplete="username"
            required
            autoFocus
            error={errors.email?.message}
            {...register('email')}
          />
          <TextField
            label="Password"
            type="password"
            autoComplete="current-password"
            required
            error={errors.password?.message}
            {...register('password')}
          />
          <Button type="submit" className="w-full" loading={isSubmitting}>
            Sign in
          </Button>
        </form>
      </div>
    </div>
  )
}
