#!/usr/bin/env node
// Seeds the catalog fixtures in scripts/catalog-fixtures.json (30 categories, 30 brands, 30 attributes, 10
// prerequisite products and 30 variants) through the public REST API, so every record goes through the same
// validation as the admin UI. Product creation also queues the normal embedding sync; catalog records are not
// embedded.
//
// Usage (backend must be running against a development database):
//   node scripts/seed-catalog.mjs          asks for confirmation
//   node scripts/seed-catalog.mjs --yes    no prompt
//   node scripts/seed-catalog.mjs --verify-only
// ADMIN_EMAIL / ADMIN_PASSWORD are read from the environment, then from the project-root .env file, and are
// prompted for if still missing. API_BASE_URL defaults to http://localhost:8080/api/v1.
//
// Safety: only localhost targets are accepted. The script is idempotent: categories and brands are matched by slug
// (or name), attributes by category + slug, products and variants by SKU. Existing records are reused as they
// are; nothing is updated or deleted. The run ends with a verification of every fixture and exits non-zero when
// anything is missing or failed.

import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createInterface } from 'node:readline/promises'
import { stdin, stdout, env, exit, argv, loadEnvFile } from 'node:process'

const envFile = fileURLToPath(new URL('../.env', import.meta.url))
if (existsSync(envFile)) {
  loadEnvFile(envFile)
}

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]', '::1'])
const baseUrl = (env.API_BASE_URL ?? 'http://localhost:8080/api/v1').replace(/\/$/, '')
const assumeYes = argv.includes('--yes')
const verifyOnly = argv.includes('--verify-only')

let headers = { 'Content-Type': 'application/json' }
const failures = []

async function prompt(question) {
  const rl = createInterface({ input: stdin, output: stdout })
  try {
    return (await rl.question(question)).trim()
  } finally {
    rl.close()
  }
}

function assertLocalTarget() {
  let url
  try {
    url = new URL(baseUrl)
  } catch {
    throw new Error(`API_BASE_URL is not a valid URL: ${baseUrl}`)
  }
  if (!LOCAL_HOSTS.has(url.hostname)) {
    throw new Error(`Refusing to seed ${url.host}: only localhost backends are allowed.`)
  }
}

async function api(method, path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = null
  }
  return { status: response.status, body: json, text }
}

async function getOk(path) {
  const { status, body, text } = await api('GET', path)
  if (status !== 200) {
    throw new Error(`GET ${path} failed with HTTP ${status}: ${text.slice(0, 300)}`)
  }
  return body
}

async function getAllPages(path) {
  const items = []
  const separator = path.includes('?') ? '&' : '?'
  for (let page = 0; ; page++) {
    const body = await getOk(`${path}${separator}page=${page}&size=100`)
    items.push(...body.content)
    if (body.last || body.content.length === 0) {
      return items
    }
  }
}

function describeError(result) {
  const message = result.body?.message ?? result.text.slice(0, 300)
  const fields = (result.body?.fieldErrors ?? []).map((f) => `${f.field}: ${f.message}`).join('; ')
  return `HTTP ${result.status} ${result.body?.code ?? ''} ${message}${fields ? ` (${fields})` : ''}`.trim()
}

function fail(what, detail) {
  failures.push(`${what}: ${detail}`)
  console.log(`FAILED   ${what}: ${detail}`)
}

async function login() {
  const email = env.ADMIN_EMAIL || (await prompt('Admin email: '))
  const password = env.ADMIN_PASSWORD || (await prompt('Admin password: '))
  if (!email || !password) {
    throw new Error('Admin email and password are required (ADMIN_EMAIL / ADMIN_PASSWORD).')
  }
  const result = await api('POST', '/auth/login', { email, password })
  if (result.status !== 200) {
    throw new Error(`Login failed with HTTP ${result.status}. Check the credentials and that the backend is running.`)
  }
  headers = { ...headers, Authorization: `Bearer ${result.body.accessToken}` }
}

const lower = (value) => value.trim().toLowerCase()

async function seedCategories(fixtures, stats) {
  const existing = await getOk('/categories/options?includeInactive=true')
  const bySlug = new Map(existing.map((c) => [c.slug, c]))
  const byName = new Map(existing.map((c) => [lower(c.name), c]))
  const ids = new Map()
  for (const category of fixtures) {
    const parentId = category.parent ? ids.get(category.parent) : null
    if (category.parent && !parentId) {
      fail(`category ${category.slug}`, `parent ${category.parent} is missing`)
      continue
    }
    const found = bySlug.get(category.slug) ?? byName.get(lower(category.name))
    if (found) {
      ids.set(category.slug, found.id)
      stats.reused++
      const note = (found.parentId ?? null) !== parentId ? ' (note: existing parent differs from the fixture)' : ''
      console.log(`reused   category ${category.slug}${note}`)
      continue
    }
    const result = await api('POST', '/categories', {
      name: category.name,
      slug: category.slug,
      description: category.description,
      parentId,
      status: category.status,
    })
    if (result.status === 201) {
      ids.set(category.slug, result.body.id)
      stats.created++
      console.log(`created  category ${category.slug}`)
    } else {
      fail(`category ${category.slug}`, describeError(result))
    }
  }
  return ids
}

async function seedBrands(fixtures, stats) {
  const existing = await getOk('/brands/options?includeInactive=true')
  const bySlug = new Map(existing.map((b) => [b.slug, b]))
  const byName = new Map(existing.map((b) => [lower(b.name), b]))
  const ids = new Map()
  for (const brand of fixtures) {
    const found = bySlug.get(brand.slug) ?? byName.get(lower(brand.name))
    if (found) {
      ids.set(brand.slug, found.id)
      stats.reused++
      console.log(`reused   brand ${brand.slug}`)
      continue
    }
    const result = await api('POST', '/brands', {
      name: brand.name,
      slug: brand.slug,
      description: brand.description,
      websiteUrl: brand.websiteUrl,
      status: brand.status,
    })
    if (result.status === 201) {
      ids.set(brand.slug, result.body.id)
      stats.created++
      console.log(`created  brand ${brand.slug}`)
    } else {
      fail(`brand ${brand.slug}`, describeError(result))
    }
  }
  return ids
}

async function existingAttributes(categoryIds, fixtures) {
  const byCategory = new Map()
  for (const slug of new Set(fixtures.map((a) => a.category))) {
    const id = categoryIds.get(slug)
    if (id) {
      byCategory.set(id, await getAllPages(`/product-attributes?categoryId=${id}`))
    }
  }
  return byCategory
}

async function seedAttributes(fixtures, categoryIds, stats) {
  const byCategory = await existingAttributes(categoryIds, fixtures)
  for (const attribute of fixtures) {
    const categoryId = categoryIds.get(attribute.category)
    if (!categoryId) {
      fail(`attribute ${attribute.slug}`, `category ${attribute.category} is missing`)
      continue
    }
    const found = (byCategory.get(categoryId) ?? []).find(
      (a) => a.slug === attribute.slug || lower(a.name) === lower(attribute.name),
    )
    if (found) {
      stats.reused++
      console.log(`reused   attribute ${attribute.category}/${attribute.slug}`)
      continue
    }
    const result = await api('POST', '/product-attributes', {
      categoryId,
      name: attribute.name,
      slug: attribute.slug,
      dataType: attribute.dataType,
      options: attribute.options,
      unit: attribute.unit,
      required: attribute.required,
      filterable: attribute.filterable,
      status: attribute.status,
    })
    if (result.status === 201) {
      stats.created++
      console.log(`created  attribute ${attribute.category}/${attribute.slug}`)
    } else {
      fail(`attribute ${attribute.slug}`, describeError(result))
    }
  }
}

async function findProductBySku(sku) {
  const page = await getOk(`/products?q=${encodeURIComponent(sku)}&size=100`)
  return page.content.find((p) => p.sku.toUpperCase() === sku.toUpperCase()) ?? null
}

async function findVariantBySku(sku) {
  const page = await getOk(`/product-variants?sku=${encodeURIComponent(sku)}&size=100`)
  return page.content.find((v) => v.sku.toUpperCase() === sku.toUpperCase()) ?? null
}

async function seedProducts(fixtures, categoryIds, brandIds, stats) {
  const ids = new Map()
  for (const product of fixtures) {
    const existing = await findProductBySku(product.sku)
    if (existing) {
      ids.set(product.sku, existing.id)
      stats.reused++
      console.log(`reused   product ${product.sku}`)
      continue
    }
    const categoryId = categoryIds.get(product.category)
    if (!categoryId) {
      fail(`product ${product.sku}`, `category ${product.category} is missing`)
      continue
    }
    const result = await api('POST', '/products', {
      name: product.name,
      sku: product.sku,
      description: product.description,
      categoryId,
      brandId: brandIds.get(product.brand) ?? null,
      price: product.price,
      currency: product.currency,
      stockQuantity: product.stockQuantity,
      attributes: product.attributes,
    })
    if (result.status === 201) {
      ids.set(product.sku, result.body.id)
      stats.created++
      console.log(`created  product ${product.sku}`)
    } else {
      fail(`product ${product.sku}`, describeError(result))
    }
  }
  return ids
}

async function seedVariants(fixtures, productIds, stats) {
  for (const variant of fixtures) {
    const existing = await findVariantBySku(variant.sku)
    if (existing) {
      stats.reused++
      console.log(`reused   variant ${variant.sku}`)
      continue
    }
    const productId = productIds.get(variant.product)
    if (!productId) {
      fail(`variant ${variant.sku}`, `product ${variant.product} is missing`)
      continue
    }
    const result = await api('POST', '/product-variants', {
      productId,
      sku: variant.sku,
      variantName: variant.variantName,
      attributes: variant.attributes,
      price: variant.price,
      salePrice: variant.salePrice,
      stockQuantity: variant.stockQuantity,
      status: variant.status,
    })
    if (result.status === 201) {
      stats.created++
      console.log(`created  variant ${variant.sku}`)
    } else {
      fail(`variant ${variant.sku}`, describeError(result))
    }
  }
}

/** Re-reads everything and checks that each fixture exists exactly once with the expected links. */
async function verify(fixtures) {
  const problems = []
  const report = []

  const categories = await getOk('/categories/options?includeInactive=true')
  const categoryBySlug = new Map(categories.map((c) => [c.slug, c]))
  let categoryCount = 0
  for (const c of fixtures.categories) {
    const found = categoryBySlug.get(c.slug)
    if (!found) {
      problems.push(`category ${c.slug} is missing`)
      continue
    }
    categoryCount++
    const expectedParent = c.parent ? (categoryBySlug.get(c.parent)?.id ?? 'missing') : null
    if ((found.parentId ?? null) !== expectedParent) {
      problems.push(`category ${c.slug} has a different parent than the fixture`)
    }
  }
  report.push(['categories', categoryCount, fixtures.categories.length])

  const brands = await getOk('/brands/options?includeInactive=true')
  const brandSlugs = new Set(brands.map((b) => b.slug))
  const brandCount = fixtures.brands.filter((b) => brandSlugs.has(b.slug)).length
  fixtures.brands.filter((b) => !brandSlugs.has(b.slug)).forEach((b) => problems.push(`brand ${b.slug} is missing`))
  report.push(['brands', brandCount, fixtures.brands.length])

  const categoryIds = new Map(categories.map((c) => [c.slug, c.id]))
  const attributesByCategory = await existingAttributes(categoryIds, fixtures.attributes)
  let attributeCount = 0
  for (const a of fixtures.attributes) {
    const matches = (attributesByCategory.get(categoryIds.get(a.category)) ?? []).filter((x) => x.slug === a.slug)
    if (matches.length === 1) {
      attributeCount++
    } else {
      problems.push(`attribute ${a.category}/${a.slug} found ${matches.length} times`)
    }
  }
  report.push(['attributes', attributeCount, fixtures.attributes.length])

  let productCount = 0
  for (const p of fixtures.products) {
    if (await findProductBySku(p.sku)) {
      productCount++
    } else {
      problems.push(`product ${p.sku} is missing`)
    }
  }
  report.push(['prerequisite products', productCount, fixtures.products.length])

  let variantCount = 0
  for (const v of fixtures.variants) {
    const found = await findVariantBySku(v.sku)
    if (!found) {
      problems.push(`variant ${v.sku} is missing`)
    } else if (found.productSku.toUpperCase() !== v.product.toUpperCase()) {
      problems.push(`variant ${v.sku} belongs to ${found.productSku}, expected ${v.product}`)
    } else {
      variantCount++
    }
  }
  report.push(['variants', variantCount, fixtures.variants.length])

  console.log('\nVerification:')
  for (const [label, found, expected] of report) {
    console.log(`  ${label.padEnd(22)} ${String(found).padStart(2)}/${expected}`)
  }
  return problems
}

async function main() {
  assertLocalTarget()
  const fixtures = JSON.parse(await readFile(new URL('./catalog-fixtures.json', import.meta.url), 'utf8'))
  console.log(`Target backend: ${baseUrl}`)
  if (!verifyOnly && !assumeYes) {
    const answer = await prompt('Seed the catalog fixtures into the database used by this backend? (y/N) ')
    if (lower(answer) !== 'y' && lower(answer) !== 'yes') {
      console.log('Cancelled; nothing was changed.')
      return
    }
  }
  await login()

  if (!verifyOnly) {
    const stats = { created: 0, reused: 0 }
    const categoryIds = await seedCategories(fixtures.categories, stats)
    const brandIds = await seedBrands(fixtures.brands, stats)
    await seedAttributes(fixtures.attributes, categoryIds, stats)
    const productIds = await seedProducts(fixtures.products, categoryIds, brandIds, stats)
    await seedVariants(fixtures.variants, productIds, stats)
    console.log(`\nSeed run: ${stats.created} created, ${stats.reused} reused, ${failures.length} failed.`)
  }

  const problems = await verify(fixtures)
  if (failures.length > 0 || problems.length > 0) {
    console.log('\nProblems:')
    ;[...failures, ...problems].forEach((p) => console.log(`  - ${p}`))
    exit(1)
  }
  console.log('\nAll catalog fixtures are present exactly once.')
}

main().catch((error) => {
  console.error(error.message)
  exit(1)
})
