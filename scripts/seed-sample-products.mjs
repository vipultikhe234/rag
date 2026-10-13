#!/usr/bin/env node
// Creates the sample products in scripts/sample-products.json through the public REST API, so they go
// through validation, the transactional outbox and vector synchronization exactly like UI-created products.
//
// Usage (backend must be running):
//   node scripts/seed-sample-products.mjs
// ADMIN_EMAIL / ADMIN_PASSWORD are read from the environment, then from the project-root .env file,
// and are prompted for if still missing. Optional: API_BASE_URL (default http://localhost:8080/api/v1).
// Existing SKUs are skipped; nothing is updated or deleted.

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
