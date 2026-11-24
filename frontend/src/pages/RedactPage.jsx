import { useRef, useState } from 'react'
import { Count, Scramble } from '../motion.jsx'
import { fetchRedactSample, redactText } from '../api.js'

const MAX_CHARS = 1_000_000
const PLACEHOLDER = /(\[(?:LEAKED-PASSWORD|PASSWORD|PIN|OTP|API-KEY|TOKEN|SECRET)-\d+\])/
const KIND_CLASS = {
  'LEAKED-PASSWORD': 'hl-leaked',
  PASSWORD: 'hl-keyboard',
  PIN: 'hl-name',
  OTP: 'hl-name',
  'API-KEY': 'hl-year',
  TOKEN: 'hl-year',
  SECRET: 'hl-english',
}
const KIND_TEXT = {
  'LEAKED-PASSWORD': 'Leaked password',
  PASSWORD: 'Password',
  PIN: 'PIN',
  OTP: 'One-time code',
  'API-KEY': 'API key',
  TOKEN: 'Access token',
  SECRET: 'Secret',
}

const kindOf = (placeholder) => placeholder.slice(1, placeholder.lastIndexOf('-'))

export default function RedactPage() {
  const [text, setText] = useState('')
  const [scanned, setScanned] = useState(null)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [copied, setCopied] = useState(false)
  const reportRef = useRef(null)

  async function run(input = text) {
    setLoading(true)
    setError(null)
    try {
      const r = await redactText(input)
      setResult(r)
      setScanned(input)
      setCopied(false)
      requestAnimationFrame(() => reportRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  async function loadSample() {
    setError(null)
    try {
      const sample = await fetchRedactSample()
      setText(sample)
      await run(sample)
    } catch (e) {
      setError(e.message)
    }
  }

  async function onFile(file) {
    if (!file) return
    if (file.size > 2 * MAX_CHARS) {
      setError('That file is over 2 MB. Paste a smaller part of it.')
      return
    }
    setText(await file.text())
    setResult(null)
  }

  async function copy() {
    await navigator.clipboard.writeText(result.redacted)
    setCopied(true)
  }

  function download() {
    const url = URL.createObjectURL(new Blob([result.redacted], { type: 'text/plain' }))
    const a = Object.assign(document.createElement('a'), { href: url, download: 'redacted.txt' })
    a.click()
    URL.revokeObjectURL(url)
  }

  const stale = result && text !== scanned

  return (
    <section aria-labelledby="redact-title">
      <h1 id="redact-title" className="display">
        <Scramble text="Redact secrets before you share" />
      </h1>
      <p className="lede">
        Paste a chat export, meeting notes or a config file. Every password, PIN, one-time code and access token is swapped for a placeholder
        like <code>[PASSWORD-1]</code>, so the text is safe to forward. Words that are also leaked passwords are caught even without a
        “password:” in front of them.
      </p>
      <p className="credit">
        Thanks to{' '}
        <a href="https://github.com/aaryaman1211/Redactor" target="_blank" rel="noreferrer">
          aaryaman1211
        </a>
        , whose PII Redactor inspired this page.
      </p>

      <div className="redact-input">
        <label htmlFor="redact-text">Text to scan</label>
        <textarea
          id="redact-text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={'[09:05] meera: VPN password for the interns is Kestrel@2026\n[09:11] rohan: staging pwd: Summer2024!'}
          spellCheck={false}
          rows={12}
          maxLength={MAX_CHARS}
        />
        <div className="redact-actions">
          <button type="button" className="primary" onClick={() => run()} disabled={loading || !text.trim()}>
            {loading ? 'Scanning…' : 'Scan and redact'}
          </button>
          <label className="link-button file-link">
            <input type="file" accept=".txt,.md,.log,.csv,.env,.json,.yml,.yaml,text/plain" onChange={(e) => onFile(e.target.files[0])} />
            Open a text file
          </label>
          <button type="button" className="link-button" onClick={loadSample} disabled={loading}>
            Try the sample team chat
          </button>
          <span className="redact-count num">
            {text.length.toLocaleString()} / {MAX_CHARS.toLocaleString()} characters
          </span>
        </div>
      </div>

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      {result && (
        <div className={stale ? 'report is-stale' : 'report'} ref={reportRef}>
          <Headline result={result} />

          <div className="table-head">
            <h2>Safe to share</h2>
            <div className="redact-out-actions">
              <button type="button" className="link-button" onClick={copy}>
                {copied ? 'Copied' : 'Copy text'}
              </button>
              <button type="button" className="link-button" onClick={download}>
                Download .txt
              </button>
            </div>
          </div>
          {stale && <p className="footnote">The text has changed since this scan. Scan again to update.</p>}
          <pre className="redacted" tabIndex={0} aria-label="Redacted text">
            {result.redacted.split(PLACEHOLDER).map((part, i) =>
              i % 2 ? (
                <mark key={i} className={`hl ${KIND_CLASS[kindOf(part)]}`}>
                  {part}
                </mark>
              ) : (
                part
              ),
            )}
          </pre>
          {Object.keys(result.byKind).length > 0 && (
            <ul className="key" aria-label="Placeholder colours">
              {Object.entries(result.byKind).map(([kind, n]) => (
                <li key={kind} className={KIND_CLASS[kind]}>
                  <span className="swatch" />
                  {KIND_TEXT[kind]} <span className="muted">× {n}</span>
                </li>
              ))}
            </ul>
          )}

          {result.findings.length > 0 && <Findings findings={result.findings} />}
        </div>
      )}
    </section>
  )
}

function Headline({ result }) {
  const { findings, stats } = result
  const unique = new Set(findings.map((f) => f.placeholder)).size
  const leaked = new Set(findings.filter((f) => f.breachStatus === 'CONFIRMED').map((f) => f.placeholder)).size
  if (!findings.length) {
    return (
      <>
        <p className="headline">
          <mark className="hl hl-keyboard">Nothing</mark> to redact.
        </p>
        <p className="lede">
          No credential cues, key formats or leaked passwords in {stats.lines.toLocaleString()} lines. This scan looks for typed secrets, not
          names, emails or phone numbers.
        </p>
      </>
    )
  }
  return (
    <>
      <p className="headline">
        <mark className="hl hl-leaked">
          <Count value={unique} /> {unique === 1 ? 'secret' : 'secrets'}
        </mark>{' '}
        hidden in {stats.lines.toLocaleString()} lines.
      </p>
      <p className="lede">
        {leaked > 0 && (
          <>
            <strong>{leaked}</strong> of them {leaked === 1 ? 'is' : 'are'} already in the leaked list, so {leaked === 1 ? 'it' : 'they'} should
            be changed, not just hidden.{' '}
          </>
        )}
        One Aho–Corasick pass found {stats.cueMatches} cue words from {stats.cuePatterns} patterns ({stats.automatonStates} automaton states).
        Another {stats.tokensChecked} password-shaped words went through the Bloom filter: {stats.bloomHits} possibly leaked,{' '}
        {stats.bloomHits - stats.bloomFalsePositives} confirmed on disk. {stats.millis} ms in all.
      </p>
    </>
  )
}

function Findings({ findings }) {
  return (
    <>
      <h2>What was hidden, and why</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Placeholder</th>
              <th scope="col">Where</th>
              <th scope="col">Was</th>
              <th scope="col">Found because</th>
              <th scope="col">Leaked?</th>
              <th scope="col">Strength</th>
            </tr>
          </thead>
          <tbody>
            {findings.map((f) => (
              <tr key={`${f.line}:${f.column}`}>
                <td>
                  <span className={`chip ${KIND_CLASS[f.kind]}`}>{f.placeholder}</span>
                </td>
                <td className="num nowrap">
                  line {f.line}, col {f.column}
                </td>
                <td className="mono" aria-label={`${f.length} characters, starting with ${f.masked[0]}`}>
                  {f.masked}
                </td>
                <td>{f.reason}</td>
                <td className={f.breachStatus === 'CONFIRMED' ? 'status is-leaked' : 'status'}>
                  {f.breachStatus === 'CONFIRMED' ? `Leaked #${f.leakRank.toLocaleString()}` : f.breachStatus ? 'Not leaked' : '—'}
                </td>
                <td className="nowrap">{f.verdict ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="footnote">Only the first character of each secret is shown. The server never stores or logs the secrets themselves.</p>
    </>
  )
}
