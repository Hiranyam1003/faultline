import { useState } from 'react'
import CheckPage from './pages/CheckPage.jsx'
import BatchPage from './pages/BatchPage.jsx'
import AttackPage from './pages/AttackPage.jsx'
import RedactPage from './pages/RedactPage.jsx'
import BenchmarkPage from './pages/BenchmarkPage.jsx'
import AboutPage from './pages/AboutPage.jsx'

const PAGES = [
  { id: 'check', label: 'Check a password', Component: CheckPage },
  { id: 'batch', label: 'Audit a file', Component: BatchPage },
  { id: 'attack', label: 'Simulate an attack', Component: AttackPage },
  { id: 'redact', label: 'Redact secrets', Component: RedactPage },
  { id: 'bench', label: 'Benchmarks', Component: BenchmarkPage },
  { id: 'about', label: 'How it works', Component: AboutPage },
]

export default function App() {
  const [active, setActive] = useState('check')
  const { Component } = PAGES.find((p) => p.id === active)

  return (
    <>
      <header className="masthead">
        <div className="masthead-inner">
          <p className="wordmark">
            <img src="/favicon.svg" alt="" width="30" height="30" />
            <span>Faultline</span>
          </p>
          <nav aria-label="Sections">
            <ul className="tabs">
              {PAGES.map((p) => (
                <li key={p.id}>
                  <button
                    type="button"
                    className="tab"
                    aria-current={active === p.id ? 'page' : undefined}
                    onClick={() => {
                      setActive(p.id)
                      window.scrollTo(0, 0)
                    }}
                  >
                    {p.label}
                  </button>
                </li>
              ))}
            </ul>
          </nav>
        </div>
      </header>
      <main className="page" key={active}>
        <Component />
      </main>
      <footer className="footer">
        <p>Bloom filter, trie, KMP, Aho–Corasick, binary heap and max-flow, written by hand in Java and run on your own machine.</p>
        <p>Passwords are never stored or logged.</p>
      </footer>
    </>
  )
}
