import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Ports are set here rather than passed on the command line, so `npm run dev`
// is always correct and never collides with Cha Ghor (5173 / 4173).
//
// To reach this from a phone on the same Wi-Fi:  npm run dev -- --host
// Vite binds to localhost only by default, so without --host the phone gets
// connection-refused no matter how the CORS is configured.
export default defineConfig({
  plugins: [react()],
  server:  { port: 5174 },
  preview: { port: 4174 },
})
