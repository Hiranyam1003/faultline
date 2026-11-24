import { useEffect, useMemo, useRef, useState } from 'react'
import { fetchOrgDemo, simulateOrg } from '../api.js'
import { Count, Scramble } from '../motion.jsx'
import { reducedMotion } from '../useTween.js'

const ATTACKERS = [
  { id: 'online', label: 'Guessing at the login page', rate: '10 guesses a second' },
  { id: 'bcrypt', label: 'Stolen bcrypt hashes', rate: '10 thousand a second' },
  { id: 'sha1', label: 'Stolen SHA-1 hashes, one GPU', rate: '10 billion a second' },
]

const BUDGETS = [
  { seconds: 60, label: '1 minute' },
  { seconds: 3600, label: '1 hour' },
  { seconds: 86400, label: '1 day' },
  { seconds: 604800, label: '1 week' },
  { seconds: 2592000, label: '1 month' },
  { seconds: 31536000, label: '1 year' },
]

const YEAR = 31536000
const UNITS = [
  [YEAR, 'year'],
  [86400, 'day'],
  [3600, 'hour'],
  [60, 'minute'],
  [1, 'second'],
]

/** A duration in plain words: "0.7 ns", "14 minutes", "1,150 years". */
function formatDuration(s) {
  if (s == null) return 'never'
  if (s < 1e-6) return `${+(s * 1e9).toPrecision(2)} ns`
  if (s < 1e-3) return `${+(s * 1e6).toPrecision(2)} µs`
  if (s < 1) return `${+(s * 1e3).toPrecision(2)} ms`
  if (s >= YEAR * 1e6) return 'over a million years'
  for (const [size, name] of UNITS) {
    if (s >= size) {
      const n = s / size
      const shown = n >= 100 ? Math.round(n).toLocaleString() : +n.toPrecision(2)
      return `${shown} ${name}${shown === 1 ? '' : 's'}`
    }
  }
  return `${+s.toPrecision(2)} seconds`
}

const budgetLabel = (seconds) => BUDGETS.find((b) => b.seconds === seconds)?.label ?? formatDuration(seconds)

export default function AttackPage() {
  const [files, setFiles] = useState({ users: null, access: null })
  const [attacker, setAttacker] = useState('sha1')
  const [budgetSeconds, setBudgetSeconds] = useState(86400)
  const [terms, setTerms] = useState('')
  const [target, setTarget] = useState('')
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const resultsRef = useRef(null)

  useEffect(() => {
    if (result) resultsRef.current?.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' })
  }, [result])

  async function run(overrides = {}) {
    const input = { ...files, attacker, budgetSeconds, target, terms, ...overrides }
    if (!input.users) {
      setError('Choose a users file first, or load the demo organisation.')
      return
    }
    setLoading(true)
    setError(null)
    try {
      const r = await simulateOrg(input)
      setResult(r)
      if (r.cut) setTarget(r.cut.target)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  async function loadDemo() {
    setLoading(true)
    setError(null)
    try {
      const demo = await fetchOrgDemo()
      const next = {
        users: new File([demo.users], 'kestrel-users.csv', { type: 'text/csv' }),
        access: new File([demo.access], 'kestrel-access.csv', { type: 'text/csv' }),
      }
      setFiles(next)
      setTerms(demo.orgTerms)
      setTarget(demo.target)
      await run({ ...next, terms: demo.orgTerms, target: demo.target })
    } catch (e) {
      setError(e.message)
      setLoading(false)
    }
  }

  function pick(kind, file) {
    if (!file) return
    setFiles((f) => ({ ...f, [kind]: file }))
    setResult(null)
    if (kind === 'access') setTarget('')
  }

  return (
    <section aria-labelledby="attack-title">
      <h1 id="attack-title" className="display">
        <Scramble text="How fast does your organisation fall?" />
      </h1>
      <p className="lede">
        Upload your accounts and who can reach what. A simulated attacker cracks the passwords smartest-guess-first, then
        max-flow finds the smallest set of accounts to secure so the system you care about is out of reach.
      </p>

      <form
        className="setup"
        onSubmit={(e) => {
          e.preventDefault()
          run()
        }}
      >
        <fieldset className="setup-files">
          <legend>Organisation</legend>
          <FilePick
            label="Accounts"
            hint="CSV: username,role,department,password"
            file={files.users}
            onPick={(f) => pick('users', f)}
            disabled={loading}
          />
          <FilePick
            label="Access, optional"
            hint="CSV: from,to, e.g. priya.nair,HR portal"
            file={files.access}
            onPick={(f) => pick('access', f)}
            disabled={loading}
          />
          <button type="button" className="link-button" onClick={loadDemo} disabled={loading}>
            Load the Kestrel Labs demo (240 synthetic accounts)
          </button>
        </fieldset>

        <fieldset className="setup-attacker">
          <legend>Attacker</legend>
          {ATTACKERS.map((a) => (
            <label key={a.id} className={attacker === a.id ? 'choice is-on' : 'choice'}>
              <input type="radio" name="attacker" value={a.id} checked={attacker === a.id} onChange={() => setAttacker(a.id)} />
              <span className="choice-label">{a.label}</span>
              <span className="choice-rate">{a.rate}</span>
            </label>
          ))}
        </fieldset>

        <div className="setup-params">
          <div className="field">
            <label htmlFor="budget">Time the attacker has</label>
            <select id="budget" value={budgetSeconds} onChange={(e) => setBudgetSeconds(Number(e.target.value))}>
              {BUDGETS.map((b) => (
                <option key={b.seconds} value={b.seconds}>
                  {b.label}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="target">System to protect</label>
            {result?.targets?.length ? (
              <select id="target" value={target} onChange={(e) => setTarget(e.target.value)}>
                {result.targets.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            ) : (
              <input id="target" value={target} onChange={(e) => setTarget(e.target.value)} placeholder="e.g. Payroll DB" />
            )}
          </div>
          <div className="field">
            <label htmlFor="terms">Words an attacker would try first</label>
            <input id="terms" value={terms} onChange={(e) => setTerms(e.target.value)} placeholder="company name, city" />
          </div>
          <button type="submit" className="primary" disabled={loading}>
            {loading ? 'Attacking…' : result ? 'Run again' : 'Run the attack'}
          </button>
        </div>
      </form>

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <div ref={resultsRef} aria-live="polite" className={loading && result ? 'is-stale' : undefined}>
        {result && <Report key={`${result.race.millis}-${result.cut?.target}`} result={result} />}
      </div>
    </section>
  )
}

function FilePick({ label, hint, file, onPick, disabled }) {
  return (
    <label className={file ? 'pick has-file' : 'pick'}>
      <input type="file" accept=".csv,text/csv,text/plain" onChange={(e) => onPick(e.target.files[0])} disabled={disabled} />
      <span className="pick-label">{label}</span>
      <span className="pick-file">{file ? file.name : 'Choose a file'}</span>
      <span className="pick-hint">{hint}</span>
    </label>
  )
}

function Report({ result }) {
  const { race, cut, accounts } = result
  const budget = budgetLabel(race.budgetSeconds)
  const attacker = ATTACKERS.find((a) => a.id === race.attacker)

  return (
    <div className="report">
      <p className="headline">
        <mark className="hl hl-leaked">
          <Count value={race.compromised} /> of <Count value={result.users} />
        </mark>{' '}
        accounts fall within {budget}.
      </p>
      <p className="lede">
        Attacker: {attacker.label.toLowerCase()}, {attacker.rate}. The race tried {race.guessesTried.toLocaleString()} guesses in order
        of likelihood and cracked {race.crackedByRace} accounts outright; the rest are timed from their strength estimate. A{' '}
        {(race.repeatFilterBytes / 1e6).toFixed(1)} MB Bloom filter skipped {race.repeatsSkipped.toLocaleString()} repeated guesses.
        Simulated in {Math.round(race.millis).toLocaleString()} ms.
      </p>

      {cut ? <Decision cut={cut} race={race} accounts={accounts} /> : <p className="footnote">Add an access file to see paths to your systems and the smallest fix.</p>}
      <Race accounts={accounts} race={race} />
      <Accounts accounts={accounts} race={race} hasCut={Boolean(cut)} />

      {result.warnings.length > 0 && (
        <>
          <h2>Rows that were skipped</h2>
          <ul className="invalid">
            {result.warnings.map((w) => (
              <li key={`${w.file}${w.line}${w.reason}`}>
                <span className="mono">
                  {w.file}.csv line {w.line}
                </span>{' '}
                {w.reason}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

function Decision({ cut, race, accounts }) {
  const [applied, setApplied] = useState(false)
  const byName = useMemo(() => new Map(accounts.map((a) => [a.username, a])), [accounts])
  const raceEnd = race.guessesTried / race.guessesPerSecond
  const afterText = (s) => (s != null && s <= raceEnd * 1.000001 ? `more than ${budgetLabel(race.budgetSeconds)}` : formatDuration(s))

  if (cut.accountsToFix === 0) {
    return (
      <>
        <h2>{cut.target} is out of reach</h2>
        <p>
          No account the attacker cracks within {budgetLabel(race.budgetSeconds)} has a path to {cut.target}.{' '}
          {cut.secondsToTargetBefore != null
            ? `The quickest way in would take ${formatDuration(cut.secondsToTargetBefore)}.`
            : 'No account can reach it at all.'}
        </p>
      </>
    )
  }

  return (
    <>
      <div className="decision">
        <div className="decision-claim">
          <h2>
            Secure {cut.accountsToFix} account{cut.accountsToFix === 1 ? '' : 's'} and {cut.target} is out of reach
          </h2>
          <p>
            {cut.compromisedWithAccess} cracked accounts have a path to {cut.target}. Max-flow shows you only need to cut{' '}
            {cut.accountsToFix} of them, because every path runs through one of these. Reset the password, turn on MFA and end open
            sessions.
          </p>
          <dl className="before-after">
            <div>
              <dt>Time to reach {cut.target} now</dt>
              <dd className="is-bad">{formatDuration(cut.secondsToTargetBefore)}</dd>
            </div>
            <div>
              <dt>After securing these</dt>
              <dd className="is-good">{afterText(cut.secondsToTargetAfter)}</dd>
            </div>
          </dl>
        </div>
        <ol className="fixes">
          {cut.fix.map((name) => {
            const a = byName.get(name)
            return (
              <li key={name}>
                <span className="fix-name">{name}</span>
                <span className="fix-meta">
                  {[a?.role, a?.department].filter(Boolean).join(', ')}
                  {a?.admin && <span className="tag">admin</span>}
                </span>
                <span className="fix-why">
                  {!a?.compromised
                    ? 'Not cracked itself, but cracked accounts pass through it'
                    : a.crackedBy
                      ? `Cracked in ${formatDuration(a.crackSeconds)}: ${a.crackedBy}`
                      : `Falls in about ${formatDuration(a.crackSeconds)} (strength estimate)`}
                </span>
              </li>
            )
          })}
        </ol>
      </div>

      <div className="table-head">
        <h2>Every way in</h2>
        <label className="check">
          <input type="checkbox" checked={applied} onChange={(e) => setApplied(e.target.checked)} />
          Show the network after the fix
        </label>
      </div>
      <AttackGraph cut={cut} applied={applied} />
    </>
  )
}

const MAX_ENTRIES_SHOWN = 10
const ROW = 30

/** Layered drawing of the attack paths: cracked accounts on the left, the protected system on the right. */
function AttackGraph({ cut, applied }) {
  const layout = useMemo(() => layoutGraph(cut), [cut])
  const { columns, positions, edges, width, height, hidden } = layout

  const alive = useMemo(() => {
    const fixed = new Set(cut.fix)
    const out = new Map()
    for (const e of edges) out.set(e.from, [...(out.get(e.from) ?? []), e.to])
    const seen = new Set(cut.nodes.filter((n) => n.entry && !fixed.has(n.id)).map((n) => n.id))
    if (hidden > 0) seen.add('+more')
    const queue = [...seen]
    while (queue.length) {
      for (const w of out.get(queue.shift()) ?? []) {
        if (!seen.has(w) && !fixed.has(w)) {
          seen.add(w)
          queue.push(w)
        }
      }
    }
    return seen
  }, [cut, edges, hidden])

  const targetSafe = applied && !alive.has(cut.target)
  const dim = (id) => applied && !alive.has(id)

  return (
    <div className="graph-wrap">
      <svg
        className={applied ? 'graph is-applied' : 'graph'}
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={`Attack paths to ${cut.target}: ${cut.compromisedWithAccess} cracked accounts reach it through ${columns.length - 2} intermediate layers. Securing ${cut.fix.join(', ')} cuts every path.`}
      >
        {edges.map((e) => {
          const a = positions.get(e.from)
          const b = positions.get(e.to)
          const mid = (a.x + b.x) / 2
          const cutEdge = cut.fix.includes(e.from)
          return (
            <path
              key={`${e.from}>${e.to}`}
              className={['edge', cutEdge && 'is-cut', dim(e.from) && 'is-dim'].filter(Boolean).join(' ')}
              d={`M${a.x + 8},${a.y} C${mid},${a.y} ${mid},${b.y} ${b.x - 8},${b.y}`}
            />
          )
        })}
        {[...positions.entries()].map(([id, p]) => {
          const n = p.node
          const isTarget = n?.target
          const classes = [
            'node',
            n?.user ? 'is-user' : 'is-system',
            n?.entry && 'is-entry',
            n?.fix && 'is-fix',
            isTarget && 'is-target',
            isTarget && targetSafe && 'is-safe',
            id === '+more' && 'is-more',
            dim(id) && !isTarget && 'is-dim',
          ]
          const label = id === '+more' ? `+${hidden} more cracked accounts` : id
          const anchor = p.col === 0 ? 'end' : p.col === columns.length - 1 ? 'start' : 'middle'
          const dx = anchor === 'end' ? -14 : anchor === 'start' ? 14 : 0
          const dy = anchor === 'middle' ? -12 : 4
          return (
            <g key={id} className={classes.filter(Boolean).join(' ')} transform={`translate(${p.x},${p.y})`}>
              {n?.fix && (
                <text className="node-mark" x={dx} y={dy} textAnchor={anchor} aria-hidden="true">
                  {label}
                </text>
              )}
              {n?.user || id === '+more' ? <circle r="6" /> : <rect x="-7" y="-7" width="14" height="14" rx="2" />}
              <text x={dx} y={dy} textAnchor={anchor}>
                {label}
              </text>
              {isTarget && (
                <text className="node-sub" x={dx} y={dy + 18} textAnchor="start">
                  {targetSafe ? 'out of reach' : 'reachable'}
                </text>
              )}
            </g>
          )
        })}
      </svg>
      <ul className="key">
        <li>
          <span className="key-dot is-entry" aria-hidden="true" />
          Cracked account
        </li>
        <li>
          <span className="key-dot" aria-hidden="true" />
          Account reached by moving sideways
        </li>
        <li>
          <span className="key-box" aria-hidden="true" />
          System
        </li>
        <li>
          <span className="swatch" style={{ '--hl-color': 'var(--marker)' }} aria-hidden="true" />
          Secure this account
        </li>
      </ul>
    </div>
  )
}

function layoutGraph(cut) {
  const fixed = new Set(cut.fix)
  const entries = cut.nodes.filter((n) => n.entry).sort((a, b) => a.crackSeconds - b.crackSeconds)
  const shownEntries = new Set(entries.filter((n) => fixed.has(n.id)).map((n) => n.id))
  for (const n of entries) if (shownEntries.size < Math.max(MAX_ENTRIES_SHOWN, fixed.size)) shownEntries.add(n.id)
  const hiddenEntries = new Set(entries.filter((n) => !shownEntries.has(n.id)).map((n) => n.id))
  const rename = (id) => (hiddenEntries.has(id) ? '+more' : id)

  const edgeKeys = new Set()
  const edges = []
  for (const e of cut.edges) {
    const from = rename(e.from)
    const to = rename(e.to)
    if (from === to || edgeKeys.has(`${from}>${to}`)) continue
    edgeKeys.add(`${from}>${to}`)
    edges.push({ from, to })
  }

  const nodes = new Map(cut.nodes.filter((n) => !hiddenEntries.has(n.id)).map((n) => [n.id, n]))
  if (hiddenEntries.size) nodes.set('+more', null)
  const out = new Map()
  for (const e of edges) out.set(e.from, [...(out.get(e.from) ?? []), e.to])

  // Longest-path layering: each node sits one column right of its furthest predecessor, so an account
  // reached sideways (Dev server → admin) lands after the system that exposes it. Edges that close a
  // cycle are found by depth-first search and ignored for layering.
  const order = []
  const state = new Map()
  const forward = new Map()
  const visit = (v) => {
    state.set(v, 'open')
    for (const w of out.get(v) ?? []) {
      if (!state.has(w)) visit(w)
      if (state.get(w) !== 'open') forward.set(v, [...(forward.get(v) ?? []), w])
    }
    state.set(v, 'done')
    order.push(v)
  }
  for (const [id, n] of nodes) if ((id === '+more' || n?.entry) && !state.has(id)) visit(id)
  for (const id of nodes.keys()) if (!state.has(id)) visit(id)
  const depth = new Map([...nodes.keys()].map((id) => [id, 0]))
  for (const v of order.reverse()) for (const w of forward.get(v) ?? []) depth.set(w, Math.max(depth.get(w), depth.get(v) + 1))
  let last = 0
  for (const [id, d] of depth) if (id !== cut.target) last = Math.max(last, d)
  depth.set(cut.target, last + 1)

  const columns = Array.from({ length: last + 2 }, () => [])
  for (const id of nodes.keys()) columns[depth.get(id) ?? 1].push(id)
  columns[0].sort((a, b) => (a === '+more' ? 1 : b === '+more' ? -1 : 0))

  const width = 1000
  const height = Math.max(...columns.map((c) => c.length), 3) * ROW + 40
  const left = 230
  const right = width - 150
  const positions = new Map()
  columns.forEach((col, c) => {
    const x = columns.length === 1 ? left : left + ((right - left) * c) / (columns.length - 1)
    const offset = (height - col.length * ROW) / 2 + ROW / 2
    col.forEach((id, i) => positions.set(id, { x, y: offset + i * ROW, col: c, node: nodes.get(id) }))
  })
  return { columns, positions, edges, width, height, hidden: hiddenEntries.size }
}

const TICKS = [
  [1e-9, '1 ns'],
  [1e-6, '1 µs'],
  [1e-3, '1 ms'],
  [1, '1 s'],
  [60, '1 min'],
  [3600, '1 hour'],
  [86400, '1 day'],
  [2592000, '1 month'],
  [YEAR, '1 year'],
  [YEAR * 100, '100 years'],
  [YEAR * 1e4, '10,000 years'],
  [YEAR * 1e6, '1M years'],
]

/** Cumulative share of accounts cracked against time on a log scale, with the attacker's budget marked. */
function Race({ accounts, race }) {
  const W = 1000
  const H = 280
  const pad = { l: 44, r: 16, t: 28, b: 34 }
  const times = accounts.map((a) => a.crackSeconds)
  const lo = Math.log10(Math.max(1e-10, Math.min(...times)))
  const xMin = Math.floor(lo)
  const xMax = Math.ceil(Math.log10(race.budgetSeconds * 1e4))
  const x = (s) => pad.l + ((Math.log10(Math.max(s, 10 ** xMin)) - xMin) / (xMax - xMin)) * (W - pad.l - pad.r)
  const y = (share) => H - pad.b - share * (H - pad.t - pad.b)
  const n = accounts.length

  let d = `M${x(10 ** xMin)},${y(0)}`
  let shown = 0
  accounts.forEach((a, i) => {
    if (a.crackSeconds > 10 ** xMax) return
    d += ` H${x(a.crackSeconds)} V${y((i + 1) / n)}`
    shown = i + 1
  })
  d += ` H${W - pad.r}`
  const area = `${d} V${y(0)} Z`
  const survivors = n - shown
  const admins = accounts.filter((a) => a.admin && a.crackSeconds <= 10 ** xMax)
  const firstAdmin = admins[0]
  const half = n >= 2 && accounts[Math.ceil(n / 2) - 1]

  return (
    <>
      <h2>The race</h2>
      <div className="race-chart">
        <svg
          viewBox={`0 0 ${W} ${H}`}
          role="img"
          aria-label={`Share of accounts cracked over time. ${race.compromised} of ${n} fall within ${budgetLabel(race.budgetSeconds)}.`}
        >
          {[0, 0.25, 0.5, 0.75, 1].map((s) => (
            <g key={s} className="grid">
              <line x1={pad.l} x2={W - pad.r} y1={y(s)} y2={y(s)} />
              <text x={pad.l - 8} y={y(s) + 4} textAnchor="end">
                {s * 100}%
              </text>
            </g>
          ))}
          {TICKS.filter(([s]) => Math.log10(s) >= xMin && Math.log10(s) <= xMax).map(([s, label]) => (
            <text key={label} className="tick" x={x(s)} y={H - pad.b + 20} textAnchor="middle">
              {label}
            </text>
          ))}
          <path className="race-area" d={area} />
          <path className="race-line" d={d} />
          <g className="budget">
            <line x1={x(race.budgetSeconds)} x2={x(race.budgetSeconds)} y1={pad.t - 14} y2={H - pad.b} />
            <text x={x(race.budgetSeconds) + 6} y={pad.t - 4}>
              {budgetLabel(race.budgetSeconds)}
            </text>
          </g>
          {admins.map((a) => (
            <line key={a.username} className="admin-tick" x1={x(a.crackSeconds)} x2={x(a.crackSeconds)} y1={H - pad.b} y2={H - pad.b - 10} />
          ))}
        </svg>
      </div>
      <ul className="moments">
        <li>
          <span className="moment-time">{formatDuration(accounts[0].crackSeconds)}</span>
          First account falls{accounts[0].crackedBy ? ` (${accounts[0].crackedBy})` : ''}.
        </li>
        {half && (
          <li>
            <span className="moment-time">{formatDuration(half.crackSeconds)}</span>
            Half the organisation is in.
          </li>
        )}
        {firstAdmin && (
          <li>
            <span className="moment-time">{formatDuration(firstAdmin.crackSeconds)}</span>
            First admin account falls: {firstAdmin.username}. Admin falls are ticked on the time axis.
          </li>
        )}
        {survivors > 0 && (
          <li>
            <span className="moment-time">beyond</span>
            {survivors} accounts hold out past the chart, more than {budgetLabel(race.budgetSeconds)} × 10,000.
          </li>
        )}
      </ul>
    </>
  )
}

const ROW_LIMIT = 300

function Accounts({ accounts, race, hasCut }) {
  const [onlyCompromised, setOnlyCompromised] = useState(true)
  const rows = onlyCompromised ? accounts.filter((a) => a.compromised || a.fix) : accounts

  function download() {
    const header = 'username,role,department,falls_after_seconds,estimated,how,compromised,secure_first'
    const quote = (v) => (/[",\n]/.test(v) ? `"${v.replaceAll('"', '""')}"` : v)
    const lines = accounts.map((a) =>
      [a.username, a.role, a.department, a.crackSeconds, a.estimated, a.crackedBy ?? 'strength estimate', a.compromised, a.fix]
        .map((v) => quote(String(v)))
        .join(','),
    )
    const url = URL.createObjectURL(new Blob([[header, ...lines].join('\n')], { type: 'text/csv' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'attack-simulation.csv'
    link.click()
    URL.revokeObjectURL(url)
  }

  return (
    <>
      <div className="table-head">
        <h2>Every account</h2>
        <div className="table-tools">
          <label className="check">
            <input type="checkbox" checked={onlyCompromised} onChange={(e) => setOnlyCompromised(e.target.checked)} />
            Only accounts cracked within {budgetLabel(race.budgetSeconds)}
          </label>
          <button type="button" className="link-button" onClick={download}>
            Download as CSV
          </button>
        </div>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Account</th>
              <th scope="col">Role</th>
              <th scope="col">Department</th>
              <th scope="col">Password</th>
              <th scope="col">Falls after</th>
              <th scope="col">How</th>
            </tr>
          </thead>
          <tbody>
            {rows.slice(0, ROW_LIMIT).map((a) => (
              <tr key={a.username}>
                <td className="strong">
                  {a.username}
                  {hasCut && a.fix && <span className="tag is-fix">secure first</span>}
                </td>
                <td>
                  {a.role || <span className="muted">none</span>}
                  {a.admin && <span className="tag">admin</span>}
                </td>
                <td>{a.department || <span className="muted">none</span>}</td>
                <td className="mono">{a.masked}</td>
                <td className={a.compromised ? 'num status is-leaked' : 'num'}>{formatDuration(a.crackSeconds)}</td>
                <td>
                  {a.crackedBy ?? <span className="muted">strength estimate</span>}
                  {a.breachStatus === 'CONFIRMED' && <span className="chip hl-leaked">leaked</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length > ROW_LIMIT && (
        <p className="footnote">
          Showing the first {ROW_LIMIT} of {rows.length} accounts. The CSV has all of them.
        </p>
      )}
      {rows.length === 0 && <p className="footnote">No account falls within {budgetLabel(race.budgetSeconds)}.</p>}
    </>
  )
}
