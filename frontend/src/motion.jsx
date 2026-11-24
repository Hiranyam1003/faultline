import { useEffect, useState } from 'react'
import { randomGlyph, reducedMotion, useTween } from './useTween.js'

/** Text that "decrypts": characters cycle through random glyphs and lock in left to right. */
export function Scramble({ text, ms = 900 }) {
  const [shown, setShown] = useState(text)

  useEffect(() => {
    if (reducedMotion()) return
    const start = performance.now()
    let frame = requestAnimationFrame(function tick(now) {
      const locked = Math.floor(((now - start) / ms) * text.length)
      setShown([...text].map((c, i) => (i < locked || c === ' ' ? c : randomGlyph())).join(''))
      if (locked < text.length) frame = requestAnimationFrame(tick)
    })
    return () => cancelAnimationFrame(frame)
  }, [text, ms])

  return (
    <>
      <span className="sr-only">{text}</span>
      <span aria-hidden="true">{reducedMotion() ? text : shown}</span>
    </>
  )
}

/** A number that counts up from 0 (or its previous value) to `value`. */
export function Count({ value, format = (n) => Math.round(n).toLocaleString() }) {
  return <span className="num">{format(useTween(value, 900, 0))}</span>
}
