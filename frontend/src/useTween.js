import { useEffect, useRef, useState } from 'react'

export const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

const GLYPHS = 'ABCDEF0123456789@#$%&*!?<>/\\=+'
export const randomGlyph = () => GLYPHS[Math.floor(Math.random() * GLYPHS.length)]

/** Animates a number from its previous value (or `initial` on mount) to `target` over `ms` milliseconds. */
export function useTween(target, ms = 700, initial = target) {
  const [value, setValue] = useState(initial)
  const from = useRef(initial)

  useEffect(() => {
    const start = performance.now()
    const begin = from.current
    let frame = requestAnimationFrame(function tick(now) {
      const t = reducedMotion() ? 1 : Math.min((now - start) / ms, 1)
      const next = begin + (target - begin) * (1 - (1 - t) ** 3)
      from.current = next
      setValue(next)
      if (t < 1) frame = requestAnimationFrame(tick)
    })
    return () => cancelAnimationFrame(frame)
  }, [target, ms])

  return value
}
