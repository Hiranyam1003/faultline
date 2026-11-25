async function request(path, options) {
  let response
  try {
    response = await fetch(path, options)
  } catch {
    throw new Error('Cannot reach the Java backend. Start it with ./mvnw spring-boot:run in backend/.')
  }
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(body?.message ?? `The server answered ${response.status}.`)
  }
  if (body === null) {
    throw new Error('The Java backend did not answer. Restart the frontend dev server (npm run dev) so it forwards /api again.')
  }
  return body
}

export function auditPassword(password, username, signal) {
  return request('/api/audit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password, username }),
    signal,
  })
}

export function auditFile(file) {
  const form = new FormData()
  form.append('file', file)
  return request('/api/audit/batch', { method: 'POST', body: form })
}

export function simulateOrg({ users, access, attacker, budgetSeconds, target, terms }) {
  const form = new FormData()
  form.append('users', users)
  if (access) form.append('access', access)
  form.append('attacker', attacker)
  form.append('budgetSeconds', budgetSeconds)
  if (target) form.append('target', target)
  if (terms) form.append('terms', terms)
  return request('/api/org/simulate', { method: 'POST', body: form })
}

export const fetchOrgDemo = () => request('/api/org/demo')

export const fetchStats = () => request('/api/stats')

export const runBenchmarks = () => request('/api/benchmark', { method: 'POST' })

export const CATEGORY_LABELS = {
  keyboard: 'Keyboard walk or sequence',
  year: 'Year',
  leaked: 'Common leaked password',
  name: 'First name',
  english: 'English word',
  username: 'Your username',
  repetition: 'Repeated block',
}

export function redactText(text) {
  return request('/api/redact', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  })
}

export async function fetchRedactSample() {
  const response = await fetch('/api/redact/sample').catch(() => null)
  if (!response?.ok) throw new Error('Cannot load the sample. Is the Java backend running?')
  return response.text()
}
