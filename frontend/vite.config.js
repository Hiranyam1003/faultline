import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The Java backend runs on :8080; the dev server forwards /api calls to it (override with API_TARGET).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': process.env.API_TARGET ?? 'http://localhost:8080' },
  },
})
