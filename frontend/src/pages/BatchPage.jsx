import { Count, Scramble } from '../motion.jsx'
import { useState } from 'react'
import { auditFile, CATEGORY_LABELS } from '../api.js'

const VERDICTS = ['Very weak', 'Weak', 'Fair', 'Strong', 'Very strong']
const STATUS_TEXT = { CONFIRMED: 'Leaked', BLOOM_FALSE_POSITIVE: 'Not leaked (Bloom false positive)', NOT_PRESENT: 'Not leaked' }
const ROW_LIMIT = 300
const TILE_LIMIT = 2000

export default function BatchPage() {
  const [report, setReport] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [fileName, setFileName] = useState('')
  const [problemsOnly, setProblemsOnly] = useState(false)
  const [dragging, setDragging] = useState(false)

  async function onFile(file) {
    if (!file) return
    setFileName(file.name)
    setLoading(true)
    setError(null)
    setReport(null)
    try {
      setReport(await auditFile(file))
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  function onDrop(e) {
    e.preventDefault()
    setDragging(false)
    onFile(e.dataTransfer.files[0])
  }

  return (
    <section aria-labelledby="batch-title">
      <h1 id="batch-title" className="display">
        <Scramble text="Audit a whole team’s passwords" />
      </h1>
      <p className="lede">
        Upload a text file with one password per line, or a CSV whose first line is <code>username,password</code>. Up to 10,000 rows and
        1 MB. The report never shows the passwords themselves.
      </p>

      <label
        className={['drop', loading && 'is-busy', dragging && 'is-over'].filter(Boolean).join(' ')}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <input type="file" accept=".txt,.csv,text/plain,text/csv" onChange={(e) => onFile(e.target.files[0])} disabled={loading} />
        <span className="drop-main">
          {loading ? `Auditing ${fileName}…` : fileName ? 'Drop another file, or click to choose' : 'Drop a .txt or .csv file here, or click to choose'}
        </span>
        <span className="drop-sub">
          {fileName && !loading ? `Last file: ${fileName}. ` : ''}No file handy?{' '}
          <a href="/sample-batch.csv" download onClick={(e) => e.stopPropagation()}>
            Download the sample CSV
          </a>
        </span>
      </label>

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {report && <Report key={fileName + report.elapsedMillis} report={report} problemsOnly={problemsOnly} setProblemsOnly={setProblemsOnly} />}
    </section>
  )
}

function tileClass(row) {
  if (row.breachStatus === 'CONFIRMED') return 'tile is-leaked'
  if (row.score < 2) return 'tile is-weak'
  return 'tile is-ok'
}

function Report({ report, problemsOnly, setProblemsOnly }) {
  const pct = (n) => (report.audited ? Math.round((n / report.audited) * 100) : 0)
  const rows = problemsOnly ? report.rows.filter((r) => r.breachStatus === 'CONFIRMED' || r.score < 2 || r.reused) : report.rows
  const maxCategory = Math.max(1, ...Object.values(report.categoryCounts))
  const weak = report.rows.filter((r) => r.breachStatus !== 'CONFIRMED' && r.score < 2).length

  return (
    <div className="report">
      <p className="headline">
        <mark className="hl hl-leaked">
          <Count value={report.confirmedBreached} /> of <Count value={report.audited} />
        </mark>{' '}
        passwords are already in the leaked list.
      </p>
      <p className="lede">
        That is {pct(report.confirmedBreached)}%. Another {weak} would fall to a quick guessing attack, and {report.reusedRows} rows share a
        password with another account. Average strength is {report.averageEntropyBits} bits. The Bloom filter flagged{' '}
        {report.bloomHits} rows and the exact check cleared {report.bloomFalsePositives} of them as false positives, all in{' '}
        {report.elapsedMillis} ms.
      </p>

      <h2>Every account at a glance</h2>
      <ul className={report.rows.length <= 200 ? 'tiles is-roomy' : 'tiles'} aria-label="One square per row">
        {report.rows.slice(0, TILE_LIMIT).map((r, i) => (
          <li
            key={r.line}
            className={tileClass(r)}
            style={{ '--i': Math.min(i, 120) }}
            title={`Line ${r.line}${r.username ? `, ${r.username}` : ''}: ${STATUS_TEXT[r.breachStatus]}, ${r.verdict}${r.reused ? ', shared' : ''}`}
          >
            {r.reused && <span className="tile-shared" />}
          </li>
        ))}
        {report.invalidRows.map((r) => (
          <li key={`bad${r.line}`} className="tile is-invalid" title={`Line ${r.line}: ${r.reason}`} />
        ))}
      </ul>
      <ul className="key">
        <li>
          <span className="tile is-leaked" aria-hidden="true" />
          Leaked
        </li>
        <li>
          <span className="tile is-weak" aria-hidden="true" />
          Weak but not leaked
        </li>
        <li>
          <span className="tile is-ok" aria-hidden="true" />
          Fair or better
        </li>
        <li>
          <span className="tile is-ok" aria-hidden="true">
            <span className="tile-shared" />
          </span>
          Shared with another account
        </li>
        {report.invalidRows.length > 0 && (
          <li>
            <span className="tile is-invalid" aria-hidden="true" />
            Could not be audited
          </li>
        )}
      </ul>

      <div className="split">
        <div>
          <h2>Strength spread</h2>
          <div className="spread" role="img" aria-label={report.scoreHistogram.map((n, i) => `${VERDICTS[i]}: ${n}`).join(', ')}>
            {report.scoreHistogram.map(
              (n, i) =>
                n > 0 && (
                  <span key={i} className={`spread-part score-bg-${i}`} style={{ flexGrow: n, '--i': i }}>
                    {n / report.audited > 0.06 && n}
                  </span>
                ),
            )}
          </div>
          <ul className="key">
            {VERDICTS.map((v, i) => (
              <li key={v}>
                <span className={`swatch score-bg-${i}`} aria-hidden="true" />
                {v}: {report.scoreHistogram[i]}
              </li>
            ))}
          </ul>
        </div>

        {Object.keys(report.categoryCounts).length > 0 && (
          <div>
            <h2>Weak patterns found</h2>
            <ul className="bars">
              {Object.entries(report.categoryCounts)
                .sort((a, b) => b[1] - a[1])
                .map(([c, n], i) => (
                  <li key={c}>
                    <span className="bar-label">{CATEGORY_LABELS[c] ?? c}</span>
                    <span className="bar-track">
                      <span className={`bar-fill hl-${c}`} style={{ width: `${(n / maxCategory) * 100}%`, '--i': i }} />
                    </span>
                    <span className="bar-value">{n}</span>
                  </li>
                ))}
            </ul>
          </div>
        )}
      </div>

      {report.invalidRows.length > 0 && (
        <>
          <h2>Rows that could not be audited</h2>
          <ul className="invalid">
            {report.invalidRows.map((r) => (
              <li key={r.line}>
                <span className="mono">Line {r.line}</span> {r.reason}
              </li>
            ))}
          </ul>
        </>
      )}

      <div className="table-head">
        <h2>Every row</h2>
        <label className="check">
          <input type="checkbox" checked={problemsOnly} onChange={(e) => setProblemsOnly(e.target.checked)} />
          Only show leaked, weak or shared
        </label>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Line</th>
              <th scope="col">Username</th>
              <th scope="col">Password</th>
              <th scope="col">Leaked?</th>
              <th scope="col">Strength</th>
              <th scope="col">Patterns</th>
            </tr>
          </thead>
          <tbody>
            {rows.slice(0, ROW_LIMIT).map((r) => (
              <tr key={r.line}>
                <td className="num">{r.line}</td>
                <td>{r.username ?? <span className="muted">none</span>}</td>
                <td className="mono">
                  {r.masked}
                  {r.reused && <span className="tag">shared</span>}
                </td>
                <td>
                  <span className={r.breachStatus === 'CONFIRMED' ? 'status is-leaked' : 'status'}>{STATUS_TEXT[r.breachStatus]}</span>
                  {r.leakRank && <span className="muted"> #{r.leakRank.toLocaleString()}</span>}
                </td>
                <td className="nowrap">
                  <span className={`dot score-bg-${r.score}`} aria-hidden="true" />
                  {r.verdict}
                </td>
                <td>
                  {r.categories.length ? (
                    r.categories.map((c) => (
                      <span key={c} className={`chip hl-${c}`}>
                        {CATEGORY_LABELS[c] ?? c}
                      </span>
                    ))
                  ) : (
                    <span className="muted">none</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length > ROW_LIMIT && (
        <p className="footnote">
          Showing the first {ROW_LIMIT} of {rows.length} rows.
        </p>
      )}
      {rows.length === 0 && <p className="footnote">No rows match this filter.</p>}
    </div>
  )
}
