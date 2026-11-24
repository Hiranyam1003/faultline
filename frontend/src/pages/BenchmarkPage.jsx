import { useEffect, useState } from 'react'
import { Count, Scramble } from '../motion.jsx'
import { fetchStats, runBenchmarks } from '../api.js'

const fmt = (n) => n.toLocaleString()
const kb = (bytes) => `${fmt(Math.round(bytes / 1024))} KB`
const ratio = (baseline, ours) => (ours > 0 ? Math.round(baseline / ours) : 0)

export default function BenchmarkPage() {
  const [stats, setStats] = useState(null)
  const [report, setReport] = useState(null)
  const [error, setError] = useState(null)
  const [startedAt, setStartedAt] = useState(null)
  const [now, setNow] = useState(0)

  useEffect(() => {
    fetchStats()
      .then(setStats)
      .catch((e) => setError(e.message))
  }, [])

  useEffect(() => {
    if (!startedAt) return
    const timer = setInterval(() => setNow(performance.now()), 100)
    return () => clearInterval(timer)
  }, [startedAt])

  async function run() {
    const t = performance.now()
    setStartedAt(t)
    setNow(t)
    setError(null)
    try {
      setReport(await runBenchmarks())
    } catch (e) {
      setError(e.message)
    } finally {
      setStartedAt(null)
    }
  }

  const running = startedAt !== null
  const elapsed = running ? Math.max(0, (now - startedAt) / 1000) : 0

  return (
    <section aria-labelledby="bench-title">
      <h1 id="bench-title" className="display">
        <Scramble text="Does each algorithm earn its place?" />
      </h1>
      <p className="lede">
        Each one is raced against a simpler baseline on three input sizes. Times are the median of five runs after warm-up, and random
        inputs use fixed seeds so reruns are comparable.
      </p>

      {stats && (
        <dl className="facts">
          <div>
            <dt>Leaked passwords</dt>
            <dd>
              <Count value={stats.leakedPasswords} />
            </dd>
          </div>
          <div>
            <dt>Bloom filter, in memory</dt>
            <dd>
              <Count value={stats.bloomBytes / 1024} /> KB <small>k = {stats.bloomHashes}</small>
            </dd>
          </div>
          <div>
            <dt>Sorted hashes, on disk</dt>
            <dd>
              <Count value={stats.storeBytes / 1024} /> KB
            </dd>
          </div>
          <div>
            <dt>Trie</dt>
            <dd>
              <Count value={stats.trieWords} /> <small>words</small>
            </dd>
          </div>
        </dl>
      )}

      <div className="run">
        <button type="button" className="primary" onClick={run} disabled={running}>
          {running ? 'Running…' : report ? 'Run the benchmarks again' : 'Run the benchmarks'}
        </button>
        {running && (
          <p className="run-timer" aria-live="off">
            <span className="num">{elapsed.toFixed(1)} s</span> of about 35
            <span className="run-track">
              <span style={{ width: `${Math.min(100, (elapsed / 33) * 100)}%` }} />
            </span>
          </p>
        )}
      </div>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      {report && (
        <div className={running ? 'bench is-stale' : 'bench'} key={report.elapsedMillis}>
          <Race
            title="Lookups: Bloom precheck vs disk only"
            claim={(best) => `${best}× faster lookups`}
            note={`${fmt(report.lookup[0].queries)} lookups, ${report.lookup[0].memberShare * 100}% of them leaked passwords. The Bloom filter skips the disk for almost every password that is not leaked.`}
            rows={report.lookup.map((r) => ({
              key: r.n,
              label: `${fmt(r.n)} passwords`,
              ours: r.bloomPlusDiskMillis,
              baseline: r.diskOnlyMillis,
              extra: `${fmt(r.bloomPlusDiskProbes)} vs ${fmt(r.diskOnlyProbes)} disk reads`,
            }))}
            oursLabel="Bloom + disk"
            baselineLabel="Disk only"
          />
          <Race
            title="Pattern scan: trie vs checking every word"
            claim={(best) => `${best}× faster scans`}
            note={`${fmt(report.trie[0].passwords)} real leaked passwords scanned. Both find the same matches; the trie’s time barely grows with the dictionary.`}
            rows={report.trie.map((r) => ({
              key: r.dictionaryWords,
              label: `${fmt(r.dictionaryWords)} words`,
              ours: r.trieMillis,
              baseline: r.naiveMillis,
              extra: `${fmt(r.trieMatches)} matches each`,
            }))}
            oursLabel="Trie"
            baselineLabel="Naive"
          />
          <Race
            title="Substring search: KMP vs naive on worst-case text"
            claim={(best) => `${best}× faster search`}
            note="The text is all “a” and the pattern is 999 × “a” then “b”, the input that makes naive search slowest."
            rows={report.kmp.map((r) => ({
              key: r.textLength,
              label: `${fmt(r.textLength)} characters`,
              ours: r.kmpMillis,
              baseline: r.naiveMillis,
              extra: `naive made ${fmt(r.naiveComparisons)} comparisons`,
            }))}
            oursLabel="KMP"
            baselineLabel="Naive"
          />

          <section className="race">
            <div className="race-intro">
              <h2>Bloom filter accuracy and memory</h2>
              <p className="race-claim">
                {Math.round(report.bloom.at(-1).hashSetBytesEstimate / report.bloom.at(-1).bloomBytes)}× less memory
              </p>
              <p className="race-note">
                Measured on 100,000 passwords known not to be in the set, against the formula{' '}
                <span className="nowrap">
                  (1 − e<sup>−kn/m</sup>)<sup>k</sup>
                </span>
                .
              </p>
            </div>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Passwords n</th>
                    <th scope="col">Target</th>
                    <th scope="col">Bits m / k</th>
                    <th scope="col">Expected</th>
                    <th scope="col">Measured</th>
                    <th scope="col">Bloom</th>
                    <th scope="col">HashSet</th>
                  </tr>
                </thead>
                <tbody>
                  {report.bloom.map((r) => (
                    <tr key={`${r.n}-${r.targetFpr}`}>
                      <td className="num">{fmt(r.n)}</td>
                      <td className="num">{r.targetFpr * 100}%</td>
                      <td className="num">
                        {fmt(r.bits)} / {r.hashes}
                      </td>
                      <td className="num">{(r.theoreticalFpr * 100).toFixed(3)}%</td>
                      <td className="num strong">{(r.measuredFpr * 100).toFixed(3)}%</td>
                      <td className="num strong">{kb(r.bloomBytes)}</td>
                      <td className="num">{kb(r.hashSetBytesEstimate)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          <TableSection
            title="Guessing: best-first vs brute force"
            claim={`${report.guessing.at(-1).bestFirstCracked} vs ${report.guessing.at(-1).bruteForceCracked} accounts cracked`}
            note={`Passwords of a ${fmt(report.guessing[0].accounts)}-person synthetic organisation. Best-first takes guesses from the binary heap in order of likelihood; brute force tries every [a-z0-9] string, shortest first.`}
            head={['Guesses', 'Best-first (heap)', 'Brute force']}
            rows={report.guessing.map((r) => [fmt(r.guesses), fmt(r.bestFirstCracked), fmt(r.bruteForceCracked)])}
          />
          <TableSection
            title="Skipping repeated guesses: Bloom filter vs HashSet"
            claim={`${ratio(report.repeats.at(-1).hashSetBytesEstimate, report.repeats.at(-1).bloomBytes)}× less memory`}
            note="The same guess can come from two word lists. The Bloom filter remembers what was tried in fixed memory; its price is a few new guesses wrongly skipped as repeats, counted here against the exact HashSet."
            head={['Guesses', 'Bloom', 'HashSet', 'Bloom time', 'HashSet time', 'Wrongly skipped']}
            rows={report.repeats.map((r) => [
              fmt(r.guesses),
              kb(r.bloomBytes),
              kb(r.hashSetBytesEstimate),
              `${r.bloomMillis} ms`,
              `${r.hashSetMillis} ms`,
              `${fmt(r.newGuessesWronglySkipped)} (${((r.newGuessesWronglySkipped / r.guesses) * 100).toFixed(3)}%)`,
            ])}
          />
          <TableSection
            title="Min cut: Edmonds–Karp vs Ford–Fulkerson"
            claim={`Cut of ${fmt(report.flow.at(-1).minCut)} found in ${Math.round(report.flow.at(-1).edmondsKarpMillis)} ms`}
            strong={3}
            note="Synthetic organisations with 40% of accounts cracked, protecting Payroll DB. Both find the same cut with the same number of augmenting paths: on this graph every path is short, so breadth-first order gives no speed-up. Edmonds–Karp’s advantage is its guarantee, O(V·E²) whatever the capacities."
            head={['Accounts', 'Vertices / edges', 'Cracked', 'Min cut', 'Edmonds–Karp', 'Ford–Fulkerson']}
            rows={report.flow.map((r) => [
              fmt(r.users),
              `${fmt(r.vertices)} / ${fmt(r.edges)}`,
              fmt(r.entryAccounts),
              fmt(r.minCut),
              `${r.edmondsKarpMillis} ms`,
              `${r.fordFulkersonMillis} ms`,
            ])}
          />
          <TableSection
            title="Many patterns at once: Aho–Corasick vs KMP"
            claim={`${ratio(report.multiPattern.at(-1).kmpPerPatternMillis, report.multiPattern.at(-1).ahoCorasickMillis)}× faster at ${fmt(report.multiPattern.at(-1).patterns)} patterns`}
            note={`Scanning ${fmt(report.multiPattern[0].textLength)} characters of synthetic chat for the most common leaked passwords, as the redactor does for credential cues. KMP needs one pass per pattern; Aho–Corasick needs one pass in total, so its time barely grows. Both report the same matches.`}
            head={['Patterns', 'Automaton states', 'Aho–Corasick', 'KMP, once per pattern', 'Matches']}
            strong={2}
            rows={report.multiPattern.map((r) => [
              fmt(r.patterns),
              fmt(r.automatonStates),
              `${r.ahoCorasickMillis} ms`,
              `${r.kmpPerPatternMillis} ms`,
              r.ahoCorasickMatches === r.kmpMatches ? fmt(r.ahoCorasickMatches) : `${fmt(r.ahoCorasickMatches)} ≠ ${fmt(r.kmpMatches)}`,
            ])}
          />
          <p className="footnote">All experiments finished in {(report.elapsedMillis / 1000).toFixed(1)} s.</p>
        </div>
      )}
    </section>
  )
}

function TableSection({ title, claim, note, head, rows, strong = 1 }) {
  return (
    <section className="race">
      <div className="race-intro">
        <h2>{title}</h2>
        <p className="race-claim">{claim}</p>
        <p className="race-note">{note}</p>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              {head.map((h) => (
                <th key={h} scope="col">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((cells) => (
              <tr key={cells[0]}>
                {cells.map((c, i) => (
                  <td key={i} className={i === strong ? 'num strong' : 'num'}>
                    {c}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function Race({ title, claim, note, rows, oursLabel, baselineLabel }) {
  const max = Math.max(...rows.map((r) => r.baseline))
  const best = Math.max(...rows.map((r) => ratio(r.baseline, r.ours)))
  return (
    <section className="race">
      <div className="race-intro">
        <h2>{title}</h2>
        <p className="race-claim">Up to {claim(fmt(best))}</p>
        <p className="race-note">{note}</p>
      </div>
      <ul className="lanes">
        {rows.map((r, i) => (
          <li key={r.key}>
            <p className="lane-head">
              <span>{r.label}</span>
              <span className="lane-ratio">{fmt(ratio(r.baseline, r.ours))}×</span>
            </p>
            <Bar label={oursLabel} value={r.ours} max={max} ours i={i * 2} />
            <Bar label={baselineLabel} value={r.baseline} max={max} i={i * 2 + 1} />
            <p className="lane-extra">{r.extra}</p>
          </li>
        ))}
      </ul>
    </section>
  )
}

function Bar({ label, value, max, ours, i }) {
  return (
    <div className="lane">
      <span className="lane-label">{label}</span>
      <span className="lane-track">
        <span className={ours ? 'lane-fill is-ours' : 'lane-fill'} style={{ width: `${Math.max(0.5, (value / max) * 100)}%`, '--i': i }} />
      </span>
      <span className="lane-value">{value} ms</span>
    </div>
  )
}
