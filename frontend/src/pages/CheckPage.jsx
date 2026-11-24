import { useEffect, useRef, useState } from 'react'
import { auditPassword, CATEGORY_LABELS } from '../api.js'
import { randomGlyph, reducedMotion, useTween } from '../useTween.js'
import { Scramble } from '../motion.jsx'

const EXAMPLES = ['P@ssw0rd2019!', 'abcabcabc', 'michael1990', 'bluefox136', 'correct-horse-battery-staple']
const GUESSES_PER_SECOND = 1e10

export default function CheckPage() {
  const [password, setPassword] = useState('')
  const [username, setUsername] = useState('')
  const [visible, setVisible] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const runs = useRef(0)

  useEffect(() => {
    if (!password) return
    const controller = new AbortController()
    const timer = setTimeout(() => {
      setLoading(true)
      auditPassword(password, username, controller.signal)
        .then((r) => {
          runs.current += 1
          setResult({ ...r, password, run: runs.current })
          setError(null)
        })
        .catch((e) => {
          if (e.name === 'AbortError') return
          setResult(null)
          setError(e.message)
        })
        .finally(() => setLoading(false))
    }, 300)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [password, username])

  const shown = password && result ? result : null
  const stale = Boolean(shown) && (loading || shown.password !== password)

  function tryExample(ex) {
    setPassword(ex)
    setVisible(true)
  }

  return (
    <section aria-labelledby="check-title">
      <h1 id="check-title" className="display">
        <Scramble text="Would an attacker guess this password?" />
      </h1>
      <p className="lede">
        It is compared with 1 million leaked passwords and taken apart into the words, years and keyboard patterns that cracking tools
        try first.
      </p>

      <form className="entry" onSubmit={(e) => e.preventDefault()}>
        <div className="field field-password">
          <label htmlFor="password">Password</label>
          <div className="with-toggle">
            <input
              id="password"
              type={visible ? 'text' : 'password'}
              value={password}
              maxLength={128}
              autoComplete="off"
              spellCheck={false}
              placeholder="Type one to inspect it"
              onChange={(e) => setPassword(e.target.value)}
            />
            <button type="button" className="toggle" onClick={() => setVisible((v) => !v)} aria-pressed={visible}>
              {visible ? 'Hide' : 'Show'}
            </button>
          </div>
        </div>
        <div className="field">
          <label htmlFor="username">
            Username or email <span className="muted">(optional)</span>
          </label>
          <input id="username" value={username} maxLength={64} autoComplete="off" placeholder="priya.nair" onChange={(e) => setUsername(e.target.value)} />
        </div>
      </form>

      <div className="specimen" aria-live="polite">
        {!password && (
          <div className="examples">
            <p className="ghost" aria-hidden="true">
              p@ssw0rd<span className="caret" />
            </p>
            <p>
              Or try one:{' '}
              {EXAMPLES.map((ex) => (
                <button key={ex} type="button" className="example" onClick={() => tryExample(ex)}>
                  {ex}
                </button>
              ))}
            </p>
          </div>
        )}
        {password && error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {password && !error && !shown && <p className="ghost">Checking…</p>}
        {shown && (
          <div className={stale ? 'is-stale' : undefined}>
            <MarkedPassword key={shown.run} password={shown.password} patterns={shown.patterns} repetition={shown.repetition} visible={visible} />
            <Legend patterns={shown.patterns} repetition={shown.repetition} />
          </div>
        )}
      </div>

      {shown && <Findings result={shown} stale={stale} visible={visible} />}
    </section>
  )
}

function Findings({ result, stale, visible }) {
  const breached = result.breachStatus === 'CONFIRMED'
  return (
    <div className={stale ? 'findings is-stale' : 'findings'}>
      <div className="readouts">
        <div className={`readout score-${result.score}`}>
          <p className="readout-label">Verdict</p>
          <p className="readout-big verdict-word">
            <Scramble text={result.verdict} ms={500} />
          </p>
          <ScoreMeter score={result.score} />
        </div>
        <CrackTime bits={result.entropyBits} breached={breached} rank={result.leakRank} />
        <div className={breached ? 'readout is-leaked' : 'readout'}>
          <p className="readout-label">In the leaked list?</p>
          <p className="readout-big">{breached ? 'Yes' : 'No'}</p>
          <p className="readout-note">
            {breached && `Rank #${result.leakRank.toLocaleString()} of 1,000,000 by how often it was used.`}
            {result.breachStatus === 'BLOOM_FALSE_POSITIVE' && 'The Bloom filter said maybe; the exact check on disk said no.'}
            {result.breachStatus === 'NOT_PRESENT' && 'The Bloom filter ruled it out without touching the disk.'}
          </p>
        </div>
      </div>

      <AttackTicker
        key={result.run}
        password={result.password}
        bits={result.entropyBits}
        breached={breached}
        rank={result.leakRank}
        visible={visible}
      />

      <h2>What the auditor did</h2>
      <ol className="stages">
        <Stage title="Bloom filter" micros={result.timingsMicros.bloom}>
          <BloomRuler probe={result.bloomProbe} />
        </Stage>
        <Stage title="Exact check on disk" micros={result.timingsMicros.exact} skipped={!result.bloomHit}>
          <Bisection probe={result.bloomProbe} reads={result.diskProbes} status={result.breachStatus} />
        </Stage>
        <Stage title="Trie scan" micros={result.timingsMicros.trie}>
          <TrieFindings password={result.password} patterns={result.patterns} />
        </Stage>
        <Stage title="KMP checks" micros={result.timingsMicros.kmp}>
          <KmpFindings result={result} />
        </Stage>
      </ol>

      <h2>How to improve it</h2>
      <ul className="advice">
        {result.suggestions.map((s) => (
          <li key={s}>{s}</li>
        ))}
      </ul>
    </div>
  )
}

function Stage({ title, micros, skipped, children }) {
  return (
    <li className={skipped ? 'stage is-skipped' : 'stage'}>
      <div className="stage-head">
        <h3>{title}</h3>
        <span className="stage-time">{skipped ? 'skipped' : `${micros} µs`}</span>
      </div>
      {children}
    </li>
  )
}

function BloomRuler({ probe }) {
  const unset = probe.set.indexOf(false)
  return (
    <>
      <p className="hash" title="SHA-1 of the password, the key the filter is built on">
        {probe.sha1}
      </p>
      <div className="ruler" role="img" aria-label={`${probe.positions.length} bit positions out of ${probe.bits.toLocaleString()}`}>
        {probe.positions.map((bit, i) => (
          <span
            key={i}
            className={probe.set[i] ? 'bit is-set' : 'bit'}
            style={{ left: `${(bit / probe.bits) * 100}%`, '--i': i }}
            title={`Bit ${bit.toLocaleString()}: ${probe.set[i] ? '1' : '0'}`}
          />
        ))}
      </div>
      <p className="ruler-scale">
        <span>bit 0</span>
        <span>{probe.bits.toLocaleString()}</span>
      </p>
      <p className="stage-text">
        One lane per probe g<sub>i</sub> = h<sub>1</sub> + i·h<sub>2</sub>.{' '}
        {unset === -1
          ? `All ${probe.positions.length} bits are 1, so it is possibly leaked.`
          : `${probe.set.filter((b) => !b).length} of them are 0 (hollow), so it is definitely not leaked.`}
      </p>
    </>
  )
}

function Bisection({ probe, reads, status }) {
  if (status === 'NOT_PRESENT') return <p className="stage-text">Not needed. A 0 bit in the Bloom filter is always right.</p>
  const target = (parseInt(probe.sha1.slice(0, 8), 16) / 2 ** 32) * 100
  return (
    <>
      <div className="bisect" aria-hidden="true">
        {Array.from({ length: reads }, (_, i) => {
          const width = Math.max(100 / 2 ** i, 0.6)
          const left = Math.min(Math.max(target - width / 2, 0), 100 - width)
          return <span key={i} style={{ left: `${left}%`, width: `${width}%`, '--i': i }} />
        })}
      </div>
      <p className="stage-text">
        {reads} reads, each halving the range of sorted hashes.{' '}
        {status === 'CONFIRMED' ? 'Found it: confirmed leaked.' : 'Not there: the Bloom filter was a false positive.'}
      </p>
    </>
  )
}

function TrieFindings({ password, patterns }) {
  const found = patterns.filter((p) => p.category !== 'username')
  return (
    <>
      {found.length > 0 && (
        <ul className="tokens">
          {found.map((p, i) => (
            <li key={`${p.start}-${p.token}`} className={`token hl-${p.category}`} style={{ '--i': i }}>
              {p.token}
              {p.leet && <span className="token-leet">from leetspeak</span>}
            </li>
          ))}
        </ul>
      )}
      <p className="stage-text">
        Walked the 25,000-word tree from each of the {password.length} start positions
        {found.length === 0 ? ' and found no known words, names, years or keyboard walks.' : '.'}
      </p>
    </>
  )
}

function KmpFindings({ result }) {
  const { repetition: rep, containsUsername } = result
  return (
    <>
      {rep && (
        <p className="repeat" aria-label={`${rep.block} repeated ${rep.count} times`}>
          {Array.from({ length: rep.count }, (_, i) => (
            <span key={i}>{rep.block}</span>
          ))}
        </p>
      )}
      <p className="stage-text">
        {containsUsername ? 'Your username appears in it. ' : 'Your username does not appear in it. '}
        {rep ? `“${rep.block}” is repeated ${rep.count} times in a row.` : 'No repeated block.'}
      </p>
    </>
  )
}

const UNITS = [
  ['seconds', 1],
  ['minutes', 60],
  ['hours', 3600],
  ['days', 86400],
  ['years', 31557600],
  ['centuries', 3155760000],
]

function crackTime(bits) {
  const seconds = 2 ** (bits - 1) / GUESSES_PER_SECOND
  if (seconds < 1) return { value: 0, unit: 'under a second' }
  let [unit, size] = UNITS[0]
  for (const [u, s] of UNITS) if (seconds >= s) [unit, size] = [u, s]
  return { value: Math.min(seconds / size, 999999), unit }
}

function CrackTime({ bits, breached, rank }) {
  const { value, unit } = breached ? { value: 0, unit: 'instantly' } : crackTime(bits)
  const shown = useTween(value, 900, 0)
  return (
    <div className={breached || value === 0 || unit === 'seconds' || unit === 'minutes' ? 'readout is-leaked' : 'readout'}>
      <p className="readout-label">Time to crack</p>
      <p className="readout-big">
        {value > 0 && <span className="num">{Math.round(shown).toLocaleString()} </span>}
        <span className={value > 0 ? 'readout-unit' : undefined}>{unit}</span>
      </p>
      <p className="readout-note">
        {breached
          ? `It is guess number ${rank.toLocaleString()} on every attacker’s wordlist.`
          : `About ${Math.round(bits)} bits of guessing work, at 10 billion guesses a second on one GPU.`}
      </p>
    </div>
  )
}

const big = (n) => Math.floor(n).toLocaleString()

/** A simulated attacker: a wordlist replay if it leaked, otherwise brute force, shown in real time unless it would finish too fast to see. */
function AttackTicker({ password, bits, breached, rank, visible }) {
  const target = breached ? rank : 2 ** (bits - 1)
  const [tried, setTried] = useState(() => (reducedMotion() ? target : 0))
  const [guess, setGuess] = useState('')
  const cracked = tried >= target
  const slowed = breached || target / GUESSES_PER_SECOND < 1.4

  useEffect(() => {
    if (reducedMotion()) return
    const start = performance.now()
    const timer = setInterval(() => {
      const elapsed = (performance.now() - start) / 1000
      const next = slowed ? target * Math.min(elapsed / 1.4, 1) : Math.min(elapsed * GUESSES_PER_SECOND, target)
      setTried(next)
      setGuess(Array.from({ length: password.length }, randomGlyph).join(''))
      if (next >= target) clearInterval(timer)
    }, 50)
    return () => clearInterval(timer)
  }, [slowed, target, password.length])

  const reveal = visible ? password : '•'.repeat(password.length)
  const progress = (tried / target) * 100
  return (
    <section className={cracked ? 'ticker is-cracked' : 'ticker'} aria-label="Simulated attack">
      <p className="ticker-head">
        <span>{breached ? 'Wordlist attack, most common leaks first' : 'Brute force at 10 billion guesses a second'}</span>
        <span>{slowed ? 'slowed down so you can watch' : 'real time'}</span>
      </p>
      <div className="ticker-body" aria-hidden="true">
        <p>
          <span className="ticker-key">trying</span> {cracked ? reveal : guess || '…'}
        </p>
        <p>
          <span className="ticker-key">guesses</span> {big(tried)}
        </p>
        <p className="ticker-status">
          {cracked
            ? `CRACKED after ${big(target)} guesses`
            : `${progress < 0.01 ? progress.toExponential(1) : progress.toFixed(2)}% of the way there`}
          {!cracked && <span className="caret" />}
        </p>
      </div>
      <p className="sr-only">
        {breached
          ? `An attacker with a leaked-password list finds it at guess ${big(target)}.`
          : `Brute force needs about ${big(target)} guesses on average.`}
      </p>
    </section>
  )
}

function Legend({ patterns, repetition }) {
  const categories = [...new Set(patterns.map((p) => p.category))]
  if (repetition && patterns.length === 0) categories.push('repetition')
  if (categories.length === 0) return <p className="legend">No known words or patterns inside.</p>
  return (
    <ul className="legend">
      {categories.map((c) => (
        <li key={c}>
          <span className={`swatch hl-${c}`} aria-hidden="true" />
          {CATEGORY_LABELS[c]}
        </li>
      ))}
    </ul>
  )
}

function ScoreMeter({ score }) {
  return (
    <div className="meter" role="img" aria-label={`Strength ${score} out of 4`}>
      {[0, 1, 2, 3, 4].map((i) => (
        <span key={i} className={i <= score ? 'on' : ''} style={{ '--i': i }} />
      ))}
    </div>
  )
}

function MarkedPassword({ password, patterns, repetition, visible }) {
  const length = password.length
  const scan = Math.min(1100, 260 + length * 45)
  const shown = (from, to) => (visible ? password.slice(from, to) : '•'.repeat(to - from))
  const pieces = []
  let i = 0
  for (const p of patterns) {
    if (p.start > i) pieces.push(<span key={`t${i}`}>{shown(i, p.start)}</span>)
    pieces.push(
      <mark
        key={`m${p.start}`}
        className={`hl hl-${p.category}${p.leet ? ' is-leet' : ''}`}
        style={{ '--delay': `${(p.end / length) * scan}ms` }}
        title={`${CATEGORY_LABELS[p.category]}: ${p.token}${p.leet ? ' (leetspeak)' : ''}`}
      >
        {shown(p.start, p.end)}
      </mark>,
    )
    i = p.end
  }
  if (i < length) pieces.push(<span key={`t${i}`}>{shown(i, length)}</span>)
  const rep = repetition && patterns.length === 0 ? repetition : null
  return (
    <div className="marked-wrap" style={{ '--scan': `${scan}ms` }}>
      <p className="marked" aria-label={visible ? `Password ${[...password].join(' ')}` : 'Password, hidden'}>
        {rep ? (
          <>
            {rep.start > 0 && <span>{shown(0, rep.start)}</span>}
            <mark className="hl hl-repetition" style={{ '--delay': `${scan}ms` }}>
              {shown(rep.start, rep.start + rep.block.length * rep.count)}
            </mark>
            <span>{shown(rep.start + rep.block.length * rep.count, length)}</span>
          </>
        ) : (
          pieces
        )}
      </p>
      <span className="scanhead" aria-hidden="true" />
    </div>
  )
}
