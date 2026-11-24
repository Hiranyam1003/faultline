import { Scramble } from '../motion.jsx'

export default function AboutPage() {
  return (
    <section aria-labelledby="about-title" className="prose">
      <div>
        <h1 id="about-title" className="display">
        <Scramble text="How the auditor works" />
      </h1>
        <p className="lede">
          Five hand-written Java algorithms from Units 2, 4, 5 and 6 of the syllabus, each doing a job that a simpler approach does slower
          or not at all.
        </p>
      </div>
      <div className="algos">
        <article className="algo">
          <header>
            <p className="unit">Unit 6, probabilistic</p>
            <h2>Bloom filter: is it leaked?</h2>
          </header>
          <p>
            Each leaked password is hashed with SHA-1, then set k = 7 bits in a 9.6-million-bit array, using double hashing g<sub>i</sub>(x)
            = h<sub>1</sub>(x) + i·h<sub>2</sub>(x) mod m. A lookup checks the same 7 bits. If any bit is 0, the password is definitely not
            leaked. If all are 1, it is only possibly leaked, so it is confirmed by binary search over a sorted 24 MB hash file on disk. The
            filter needs about 1.2 MB, against roughly 85 MB for a Java HashSet of the same passwords.
          </p>
          <p>
            Sizing: m = ⌈−n ln p / (ln 2)²⌉ and k = round((m/n) ln 2). For n = 1,000,000 and p = 1%, that gives m ≈ 9.59 million bits and k
            = 7.
          </p>
        </article>

        <article className="algo">
          <header>
            <p className="unit">Unit 4, text search</p>
            <h2>Trie: which weak pieces does it contain?</h2>
          </header>
          <p>
            25,000 keyboard walks, years, common passwords, first names and English words share one prefix tree. The scan starts a walk at
            every position of the password and records each node that ends a word. A second pass runs on a leetspeak-normalised copy
            (p@ssw0rd → password). The longest non-overlapping matches are kept, and each one costs only log<sub>2</sub>(size of its word
            list) bits of guessing instead of a brute-force guess per character.
          </p>
        </article>

        <article className="algo">
          <header>
            <p className="unit">Unit 4, text search</p>
            <h2>KMP: username and repetition</h2>
          </header>
          <p>
            Knuth–Morris–Pratt finds the username, or the part of an email before the @, anywhere in the password in linear time. Its prefix
            function also detects repetition. A prefix of length L repeats a block of length L − π[L−1] when that length divides L, so
            abcabc1 is “abc” × 2.
          </p>
        </article>

        <article className="algo">
          <header>
            <p className="unit">Unit 2, priority queues</p>
            <h2>Binary heap: which guess next?</h2>
          </header>
          <p>
            The attack simulation guesses the way a real attacker does: most likely first. A guess is a base word (leaked password, company
            term, name, word, year) with probability p<sub>i</sub>, changed by a rule (Capitalise, add a year, add “!”) with probability q
            <sub>j</sub>, so it scores p<sub>i</sub>·q<sub>j</sub>. With both lists sorted, the guesses form a sorted grid, and a max-heap
            walks it in decreasing order. Popping cell (i, j) pushes (i, j+1), and (i+1, 0) only when j = 0, so every cell is reached exactly
            once with no visited set. Each guess costs O(log h) for a frontier of h cells.
          </p>
          <p>
            The same string can come from two lists, so a second Bloom filter remembers which guesses were already tried: about 3.6 MB
            for 2 million guesses, against roughly 180 MB for a HashSet. A false positive skips one genuinely new guess, about 0.01% of them.
          </p>
        </article>

        <article className="algo">
          <header>
            <p className="unit">Unit 5, network flow</p>
            <h2>Max-flow min-cut: the smallest fix</h2>
          </header>
          <p>
            Accounts cracked within the attacker’s time budget become entry points into a graph of who can reach what. Each account is
            split into in → out with capacity 1, the cost of securing it; systems and access edges cannot be cut. A super-source feeds every
            entry account and the protected system is the sink. Edmonds–Karp augments along shortest paths found by BFS until none is left.
            The flow value is the fewest accounts to secure, and the accounts whose in-side is still reachable from the source in the
            residual graph, but whose out-side is not, are exactly which ones.
          </p>
          <p>
            By the max-flow min-cut theorem this is optimal, not an approximation; the tests check it against brute force over every subset
            on 150 random graphs.
          </p>
        </article>

        <article className="algo">
          <header>
            <p className="unit">Unit 4, string matching</p>
            <h2>Aho–Corasick: secrets in shared text</h2>
          </header>
          <p>
            The redactor looks for 30 cues at once (“password”, “pwd”, “PIN”, “api key”, and key formats such as <code>ghp_</code> and{' '}
            <code>AKIA</code>). Running KMP once per cue would read the text 30 times. Aho–Corasick puts every cue in one trie and gives each
            node a failure link, the longest suffix that is also a trie prefix: KMP’s prefix function, extended to many patterns. On a mismatch
            the scan follows failure links, so it reads the text exactly once, in O(n + matches) whatever the number of cues.
          </p>
          <p>
            The value after each cue is replaced by a placeholder. Every other password-shaped word goes through the Bloom filter, and only
            its hits are confirmed on disk, so a leaked password like <code>P@ssw0rd</code> is caught even with no cue in front of it.
          </p>
        </article>
      </div>

      <div className="block">
        <h2>Complexity</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Operation</th>
                <th scope="col">Time</th>
                <th scope="col">Space</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>Bloom insert or lookup</td>
                <td>O(k)</td>
                <td>O(m) bits in total</td>
              </tr>
              <tr>
                <td>Exact confirmation (binary search on disk)</td>
                <td>O(log n) reads</td>
                <td>O(n) on disk, O(1) in memory</td>
              </tr>
              <tr>
                <td>Trie scan of a password of length ℓ</td>
                <td>O(ℓ · L), where L is the longest word</td>
                <td>O(total characters in the dictionary)</td>
              </tr>
              <tr>
                <td>Naive dictionary scan (baseline)</td>
                <td>O(D · ℓ · L) for D words</td>
                <td>—</td>
              </tr>
              <tr>
                <td>KMP search, text t and pattern p</td>
                <td>O(|t| + |p|)</td>
                <td>O(|p|)</td>
              </tr>
              <tr>
                <td>Naive search (baseline)</td>
                <td>O(|t| · |p|) worst case</td>
                <td>O(1)</td>
              </tr>
              <tr>
                <td>Heap insert or poll</td>
                <td>O(log h)</td>
                <td>O(h)</td>
              </tr>
              <tr>
                <td>Next best guess (heap walk over B bases × R rules)</td>
                <td>O(log h) per guess, h ≤ B</td>
                <td>O(B + R) plus the repeat filter</td>
              </tr>
              <tr>
                <td>Edmonds–Karp on V vertices and E edges</td>
                <td>O(V · E²); here O(F · E) with F ≤ cracked accounts</td>
                <td>O(V + E)</td>
              </tr>
              <tr>
                <td>Aho–Corasick scan, text n, k patterns of total length m</td>
                <td>O(n + z) for z matches, after O(m) build</td>
                <td>O(m) states</td>
              </tr>
              <tr>
                <td>KMP once per pattern (baseline)</td>
                <td>O(k · n + m)</td>
                <td>O(longest pattern)</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div className="block">
        <h2>Limits and assumptions</h2>
        <ul>
          <li>A Bloom hit only means “possibly leaked”. Only the exact disk check ever reports a password as leaked.</li>
          <li>
            The strength estimate is a simplified guess-count model, not a guarantee. Passwords outside the dictionaries can still be weak.
          </li>
          <li>The leaked list is the top 1 million passwords from public breach compilations (SecLists), not every breach ever.</li>
          <li>Passwords go to the local server for checking and are never stored or logged. Batch reports show only masked values.</li>
          <li>
            The attack simulation’s guess model is a simplified, hand-weighted PCFG-style model, not a tuned cracker. Accounts it does not reach
            within 5 million guesses are timed from the strength estimate and labelled as such. It only ever runs on the files you upload.
          </li>
          <li>
            The redactor finds typed secrets: values after a credential cue, known key formats and leaked passwords. It is rule-based, so a
            password written with no cue and not in the leaked list (“the code is on the fridge: Tr0ub4dor”) can slip through. It does not
            look for names, emails or phone numbers.
          </li>
          <li>The Kestrel Labs demo organisation is synthetic: generated names, passwords and access, seeded so it is the same every run.</li>
        </ul>
      </div>
    </section>
  )
}
