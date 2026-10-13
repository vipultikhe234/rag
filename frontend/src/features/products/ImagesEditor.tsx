import { useFieldArray, useWatch, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/FormField'
import { isHttpUrl } from '../catalog/validation'
import type { ProductFormValues } from './productForm'

const MAX_IMAGES = 20

interface ImagesEditorProps {
  control: Control<ProductFormValues>
  register: UseFormRegister<ProductFormValues>
  errors: FieldErrors<ProductFormValues>
}

/** Image URLs in display order; the first image is the main image. */
export function ImagesEditor({ control, register, errors }: ImagesEditorProps) {
  const { fields, append, remove, move } = useFieldArray({ control, name: 'images' })
  const images = useWatch({ control, name: 'images' })

  return (
    <div className="space-y-3">
      {fields.length === 0 && <p className="text-sm text-slate-500">No images yet. Add http(s) image URLs.</p>}
      {fields.map((field, index) => {
        const url = images?.[index]?.url?.trim() ?? ''
        return (
          <div key={field.id} className="flex flex-wrap items-start gap-3 rounded-md border border-slate-200 p-3">
            <div className="flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded bg-slate-100 text-xs text-slate-400">
              {isHttpUrl(url) ? (
                <img src={url} alt="" className="h-full w-full object-cover" referrerPolicy="no-referrer" />
              ) : (
                'No preview'
              )}
            </div>
            <div className="grid min-w-0 flex-1 gap-3 sm:grid-cols-[2fr_1fr]">
              <TextField
                label={index === 0 ? 'Image URL (main image)' : `Image URL ${index + 1}`}
                required
                placeholder="https://…"
                maxLength={500}
                error={errors.images?.[index]?.url?.message}
                {...register(`images.${index}.url`)}
              />
              <TextField
                label="Alt text"
                maxLength={200}
                error={errors.images?.[index]?.altText?.message}
                {...register(`images.${index}.altText`)}
              />
            </div>
            <div className="flex gap-1 pt-6">
              <Button
                variant="ghost"
                size="sm"
                disabled={index === 0}
                onClick={() => move(index, index - 1)}
                aria-label={`Move image ${index + 1} up`}
              >
                ↑
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={index === fields.length - 1}
                onClick={() => move(index, index + 1)}
                aria-label={`Move image ${index + 1} down`}
              >
                ↓
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="text-red-600 hover:bg-red-50"
                onClick={() => remove(index)}
                aria-label={`Remove image ${index + 1}`}
              >
                Remove
              </Button>
            </div>
          </div>
        )
      })}
      {errors.images?.message && <p className="text-xs text-red-600">{errors.images.message}</p>}
      <Button
        variant="secondary"
        size="sm"
        disabled={fields.length >= MAX_IMAGES}
        onClick={() => append({ id: '', url: '', altText: '' })}
      >
        Add image
      </Button>
    </div>
  )
}
