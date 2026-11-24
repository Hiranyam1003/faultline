# Faultline: presentation brief

## 1. The pitch
**Problem:** Most hacked accounts use a password that is either already in a public leak list or built from guessable pieces: a name, a year, `qwerty`, or `p@ssw0rd`-style swaps. Users only get a coloured bar that never explains why, and IT admins have no quick way to audit a whole organisation.

**What we built:** a web app that checks a password against **1 million real leaked passwords** in microseconds, takes it apart into the pieces an attacker would guess first, scores it, and explains how to fix it. It does this for one password or for a file of up to 10,000 accounts.

**Users:** college or company IT administrators running a bulk audit, and individuals checking a password before using it.

## 2. Tech stack

| Layer | What we used | Why |
|---|---|---|
| Backend | **Java 21, Spring Boot 4** (REST API) | Required by the brief. All algorithms are hand-written Java. |
| Frontend | **React + Vite** (plain CSS, no UI library) | Calls the Java API. The browser does no algorithm work. |
| Tests | **JUnit 5** + Spring MockMvc | 48 tests |
| Build | Maven wrapper (`mvnw`) and npm | Runs from the repo with no extra installs |
| Data | SecLists top 1M leaked passwords; English words, first names, keyboard walks, years | Real leaked data, MIT-licensed |
| Storage | No database. A sorted binary file on disk (24 MB) | Gives the Bloom filter a real job: avoiding disk reads |

Libraries are used only for infrastructure. SHA-1 hashing comes from the JDK and sorting from `Arrays.sort`. **The Bloom filter, trie, KMP, binary-search store and scoring DP are all our own code.**

## 3. Architecture (one diagram slide)
```
React UI ──REST──► AuditController ──► AuditService (pipeline)
                                         │
   1. SHA-1 → Bloom filter (in RAM, 1.2 MB) ── miss → "Not leaked" (disk never touched)
                    │ hit ("possibly leaked")
   2. Binary search on sorted hash file (disk) → CONFIRMED (rank #N) or FALSE POSITIVE
   3. Trie scan (normal + leetspeak copy) → words, names, years, keyboard walks
   4. KMP → username inside password? repeated block?
   5. DP picks the cheapest set of weak pieces → entropy (bits) → score 0–4 + advice
```

**REST endpoints:**

| Endpoint | Purpose |
|---|---|
| `POST /api/audit` | Check one password |
| `POST /api/audit/batch` | Audit an uploaded file |
| `GET /api/stats` | Describe the loaded index |
| `POST /api/benchmark` | Run all experiments |

**Code packages:** `algo` (Bloom, Trie, KMP), then `store`, `index`, `service` and `api`.

## 4. The algorithms (the core of the marks)

### A. Bloom filter: Unit 6, randomized/probabilistic (CO5)
- **What it is:** a bit array of m bits plus k hash functions. Inserting a password sets k bits; checking it tests those k bits.
- **Key property:** if any of the bits is 0, the password is **definitely not** in the set. If all are 1, it is only **possibly** in the set. So it has no false negatives, only false positives.
- **Our parameters:** n = 1M and target p = 1% give **m = 9.59 million bits (1.2 MB) and k = 7**.
  - Formulas: m = −n·ln p / (ln 2)², and k = (m/n)·ln 2.
  - Theoretical false-positive rate: (1 − e^(−kn/m))^k.
- **Implementation details:**
  - Bits are stored in our own `long[]` array (no `BitSet`).
  - Double hashing g_i(x) = h₁(x) + i·h₂(x) mod m: 2 hashes simulate 7.
  - The hash function is our own (FNV-style mixing + Murmur finaliser).
- **Following the brief's rule:** every hit is confirmed by an exact binary search over a sorted file of 24-byte records (20-byte SHA-1 + frequency rank).
- **Complexity:**
  - Bloom check: O(k) time.
  - Exact check: O(log n) disk reads, about 20 for 1M passwords.
  - Space: O(m) bits.
- **Live demo of a false positive:** `bluefox136`. The Bloom filter says "possibly leaked", and the disk check says "not leaked".
- **Code:** `algo/BloomFilter.java`, `store/SortedHashStore.java`

### B. Trie: Unit 4, text search
- **What it is:** a prefix tree of 25,082 weak tokens: keyboard walks, years, the top 10k leaked passwords, names and English words.
- **How we use it:** from every position in the password, walk down the trie and record each node that ends a word. This finds every dictionary word hidden anywhere inside the password.
- **Leetspeak:** a second pass runs on a normalised copy (`@`→a, `0`→o, `$`→s), so `P@ssw0rd` → `password`.
- **Complexity:**
  - Trie scan: O(ℓ·L), where ℓ is the password length and L the longest word. **It does not depend on dictionary size.**
  - Naive baseline: O(D·ℓ·L) for D words.
- **Code:** `algo/Trie.java`, `algo/LeetNormalizer.java`

### C. KMP (Knuth–Morris–Pratt): Unit 4, text search
- **What it is:** linear-time pattern matching that uses the prefix function π. π[i] is the longest proper prefix of the pattern that is also a suffix ending at position i, so matching never moves backwards.
- **Use 1: username detection.** It finds the username and its parts (`priya.nair` → `priya`, `nair`) inside the password, in O(n + m).
- **Use 2: repetition detection.** A prefix of length L repeats a block of length L − π[L−1] when that length divides L. So `abcabcabc` is "abc" × 3. It is applied at every start position, so repeats anywhere are found.
- **Complexity:** O(n + m) time against O(n·m) worst case for naive search.
- **Code:** `algo/Kmp.java`

### D. Supporting algorithm: dynamic programming for scoring
- A password can match overlapping pieces. For example, `password2019` contains `password2`, `password` and `2019`.
- The DP picks the **cheapest non-overlapping set**, the way an attacker would:
  `best[i] = min( brute-force-char + best[i+1], cost(piece) + best[piece.end] )`
- **Cost of a piece:** log₂(size of its dictionary). Your username costs 0 bits, since an attacker targeting you knows it.
- The total bits give a score from 0 to 4. Any confirmed leak scores 0.
- This works like zxcvbn, the password checker Dropbox built.
- **Code:** `service/StrengthScorer.java`

## 5. Results (the "measurable efficiency" slide)

| Experiment (3 sizes: 10k / 100k / 1M) | Result |
|---|---|
| Bloom false-positive rate: measured vs theory | **0.984% vs 1.004%** at 1M; matches at every size and at both 1% and 0.1% targets |
| Memory: Bloom vs Java HashSet | **1.2 MB vs about 85 MB** (≈ 70× smaller) |
| Lookups: Bloom + disk vs disk only | **17× faster, 18× fewer disk reads** (22k vs 398k reads) |
| Pattern scan: trie vs naive (1k → 19.7k words) | **42× → 698× faster**. Naive time grows with dictionary size; the trie stays flat. Identical matches. |
| Search on worst-case text: KMP vs naive | **up to 324× faster** (naive made ~1 billion comparisons) |
| Zero false negatives | Verified by a test that checks **all 999,999** leaked passwords |

Full tables are in `docs/benchmarks.md`. Times are the median of 5 runs after warm-up, with fixed random seeds, so the results are reproducible.

## 6. Testing and edge cases (the correctness slide, 20 marks)
**48 JUnit tests, all passing:**

| Test class | Tests |
|---|---|
| Bloom filter | 8 |
| Trie | 7 |
| KMP | 7 |
| Sorted store | 6 |
| Audit pipeline | 8 |
| Batch audit | 6 |
| End-to-end API | 6 |

Each core algorithm has at least 5 tests, including boundary cases, as the brief requires.

**Edge cases handled with clear messages:**
- **Password input:** empty, only spaces, over 128 characters, control characters.
- **Uploaded file:** empty, over 1 MB, over 10,000 rows, or not UTF-8.
- **Bad rows:** malformed CSV rows are reported by line number.
- **Duplicate passwords:** reported as "shared by several accounts", not silently dropped.
- **Bloom filter:** a test covers a deliberately saturated filter, so the false-positive path is exercised too.

## 7. How this maps to the rubric

| Criterion (marks) | What to show |
|---|---|
| Problem formulation (10) | Real users (IT admin, individual), real leaked data, success criteria in `docs/proposal.md` |
| Algorithm selection (20) | 3 hand-written techniques: 2 from Unit 4, 1 from Unit 6 for CO5. Explain why each one fits. |
| Correctness and tests (20) | 48 tests, the zero-false-negative proof, edge cases, the false-positive demo |
| Complexity and performance (15) | Complexity table plus the benchmarks against baselines at 3 sizes |
| Backend quality (10) | Layered packages (`algo` → `store` → `service` → `api`), validation, consistent JSON errors |
| Frontend (5) | Full flow from input to explanation, loading, error and empty states, works on mobile |
| Documentation (5) | README with run steps, API examples, attribution and screenshots |
| Demo and viva (15) | Script below, plus each person owning one algorithm |

## 8. Complexity summary (slide)

| Operation | Time | Space |
|---|---|---|
| Bloom insert or lookup | O(k) | O(m) bits in total |
| Exact check (binary search on disk) | O(log n) reads | O(n) on disk, O(1) in RAM |
| Trie scan | O(ℓ·L) | O(total dictionary characters) |
| Naive dictionary scan (baseline) | O(D·ℓ·L) | — |
| KMP search | O(n + m) | O(m) |
| Naive search (baseline) | O(n·m) worst case | O(1) |
| Scoring DP | O(ℓ + number of matches) | O(ℓ) |

## 9. Suggested slide outline (10–12 slides)
1. Title and team
2. The problem: leaked and guessable passwords, with one striking statistic
3. Users and what they get
4. Architecture diagram (section 3)
5. Bloom filter: bit-array picture, formulas, why no false negatives, the exact check
6. Trie: tree picture showing `pass` / `password` / `word` matching inside `password`
7. KMP: prefix-function table for `abcabc`, username and repetition uses
8. Scoring DP: the `password2019` example, greedy vs DP
9. Complexity table (section 8)
10. Benchmarks: 2–3 charts from the Benchmarks page (`docs/screenshots/benchmarks.png`)
11. Testing and edge cases
12. Limitations and future work

## 10. Eight-minute demo script
1. **Check page:** type `P@ssw0rd2019!`. Highlighted pieces appear (leaked word in leetspeak + year), it's rated "Weak", and the 4-step pipeline shows the disk was skipped. *(1.5 min)*
2. **`password123`:** confirmed leaked at rank #1085, found in 20 disk reads. *(0.5 min)*
3. **`bluefox136`:** a live Bloom false positive caught by the exact check. *This shows you understand the probabilistic part.* *(1 min)*
4. **`Michael1990abcabc` with username `michael.s@uni.edu`:** username, year and repeated block found. *(1 min)*
5. **Audit a file:** upload `data/sample-batch.csv`. Walk through the leak rate, shared passwords, the bad rows on lines 30–31, and the masked passwords. *(1.5 min)*
6. **Benchmarks:** click Run (about 20 s; talk over it) and point at the speedups. *(1.5 min)*
7. **Edge case:** submit an empty password and show the clear error. *(0.5 min)*

**Before you present:** start both servers early (`./mvnw spring-boot:run` in `backend/`, `npm run dev` in `frontend/`). The backend loads in about 2 seconds after the first run.

## 11. Likely viva questions and short answers
- **Why a Bloom filter and not a HashSet?** It's 70× less memory and never misses a leaked password. The cost is about 1% false positives, which the exact check removes.
- **Can it ever say a leaked password is safe?** No. Inserted bits are never cleared, so a member always finds all k bits set. A test checks all 999,999 passwords.
- **Why k = 7?** k = (m/n)·ln 2 minimises the false-positive rate for a given m and n.
- **Why is the measured false-positive rate slightly above theory on tiny filters?** The formula assumes a large m. At m = 1 it gives 0.63, while the true rate is 1.
- **Why double hashing?** Two hashes combined as h₁ + i·h₂ behave like k independent hashes for the false-positive rate (Kirsch–Mitzenmacher), so each check costs 2 hashes instead of 7.
- **Why a trie over checking each word?** Scan cost doesn't grow with dictionary size: 0.66 ms vs 460 ms at about 20k words.
- **What does the KMP prefix function store?** The longest proper prefix that is also a suffix at each position. That lets KMP skip re-comparisons, so it runs in O(n + m).
- **Why SHA-1 when it is "broken"?** It's only used as a fixed-size key for lookups, not for security. It's also what Have I Been Pwned uses.
- **Why DP instead of greedy for scoring?** Greedy picks `password2` and loses `2019`. The DP finds the cheapest overall split.
- **Is the score exact?** No. It's a simplified guess-count estimate, and we say so in the limitations.

## 12. Limitations and future work (stating these honestly earns marks)
**Limitations:**
- The leak list is the top 1M passwords, not every breach.
- The strength score is an estimate.
- Repetition detection is O(n²), which is fine because passwords are capped at 128 characters.
- Passwords go to a local server. They are never stored or logged.

**Future work:**
- k-anonymity hash-prefix queries against a larger leak list, like Have I Been Pwned.
- A counting Bloom filter so passwords can be removed.
- More languages in the dictionaries.

## 13. Splitting the viva
The guidelines require each person to explain their own contribution:

- **Person 1:** Bloom filter and the sorted store
- **Person 2:** Trie, leetspeak and the scoring DP
- **Person 3:** KMP, the REST API and the React frontend

Each person should know the complexity of their part and be able to walk through its Java file in `backend/src/main/java/edu/atlas/pwaudit/`.
