#!/usr/bin/env node
// Creates the sample products in scripts/sample-products.json through the public REST API, so they go
// through validation, the transactional outbox and vector synchronization exactly like UI-created products.
//
// Usage (backend must be running):
//   node scripts/seed-sample-products.mjs
// ADMIN_EMAIL / ADMIN_PASSWORD are read from the environment, then from the project-root .env file,
// and are prompted for if still missing. Optional: API_BASE_URL (default http://localhost:8080/api/v1).
// Categories and brands named by the samples are created first when missing (products must reference existing
// ones). Existing SKUs are skipped; nothing is updated or deleted.

import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createInterface } from 'node:readline/promises'
import { stdin, stdout, env, exit, loadEnvFile } from 'node:process'

const envFile = fileURLToPath(new URL('../.env', import.meta.url))
if (existsSync(envFile)) {
  loadEnvFile(envFile)
}

const baseUrl = (env.API_BASE_URL ?? 'http://localhost:8080/api/v1').replace(/\/$/, '')

async function prompt(question) {
  const rl = createInterface({ input: stdin, output: stdout })
  try {
    return (await rl.question(question)).trim()
  } finally {
    rl.close()
  }
}

/** Creates the categories or brands (by name) that do not exist yet. */
async function ensureNamed(resource, names, headers) {
  const response = await fetch(`${baseUrl}/${resource}/options?includeInactive=true`, { headers })
  if (!response.ok) {
    throw new Error(`Listing ${resource} failed with HTTP ${response.status}.`)
  }
  const existing = new Set((await response.json()).map((item) => item.name.trim().toLowerCase()))
  for (const name of new Set(names)) {
    if (existing.has(name.trim().toLowerCase())) {
      continue
    }
    const created = await fetch(`${baseUrl}/${resource}`, { method: 'POST', headers, body: JSON.stringify({ name }) })
    if (created.status !== 201 && created.status !== 409) {
      throw new Error(`Creating ${resource} entry '${name}' failed with HTTP ${created.status}: ${await created.text()}`)
    }
    existing.add(name.trim().toLowerCase())
    if (created.status === 201) {
      console.log(`created  ${resource === 'categories' ? 'category' : 'brand'} ${name}`)
    }
  }
}

async function main() {
  const email = env.ADMIN_EMAIL || (await prompt('Admin email: '))
  const password = env.ADMIN_PASSWORD || (await prompt('Admin password: '))
  if (!email || !password) {
    throw new Error('Admin email and password are required (ADMIN_EMAIL / ADMIN_PASSWORD).')
  }

  const login = await fetch(`${baseUrl}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!login.ok) {
    throw new Error(`Login failed with HTTP ${login.status}. Check the credentials and that the backend is running.`)
  }
  const { accessToken } = await login.json()
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` }

  const products = JSON.parse(await readFile(new URL('./sample-products.json', import.meta.url), 'utf8'))
  await ensureNamed('categories', products.map((p) => p.category), headers)
  await ensureNamed('brands', products.map((p) => p.brand).filter(Boolean), headers)
  let created = 0
  let skipped = 0
  for (const product of products) {
    const response = await fetch(`${baseUrl}/products`, { method: 'POST', headers, body: JSON.stringify(product) })
    if (response.status === 201) {
      created++
      console.log(`created  ${product.sku}  ${product.name}`)
    } else if (response.status === 409) {
      skipped++
      console.log(`skipped  ${product.sku}  (SKU already exists)`)
    } else {
      const body = await response.text()
      throw new Error(`Creating ${product.sku} failed with HTTP ${response.status}: ${body}`)
    }
  }
  console.log(`\nDone: ${created} created, ${skipped} skipped. Embeddings are generated asynchronously;`)
  console.log('check progress on the Product Management page or GET /api/v1/admin/vector-sync/status.')
}

main().catch((error) => {
  console.error(error.message)
  exit(1)
})
