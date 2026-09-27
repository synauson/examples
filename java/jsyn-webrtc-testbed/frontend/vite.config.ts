import { defineConfig, configDefaults } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

/**
 * Vite config for the JSyn WebRTC testbed SPA.
 *
 * In local dev, outDir writes to ../src/main/resources/static so Spring Boot
 * serves the bundle from classpath:/static without an extra copy step.
 *
 * In the Docker multi-stage build, VITE_OUT_DIR overrides the output location
 * so the build writes to /work/frontend/dist, which the backend stage then
 * copies into src/main/resources/static before running bootJar.
 *
 * During development, vite dev runs on port 5173 and proxies /ws and /api
 * back to the Spring backend on 8080.
 */
const outDir = process.env.VITE_OUT_DIR
  ? path.resolve(process.cwd(), process.env.VITE_OUT_DIR)
  : path.resolve(__dirname, '../src/main/resources/static');

export default defineConfig({
  plugins: [react()],
  build: { outDir, emptyOutDir: true, sourcemap: true },
  server: {
    port: 5173,
    proxy: {
      '/ws':  { target: 'ws://localhost:8080', ws: true },
      '/api': { target: 'http://localhost:8080' },
    },
  },
  test: {
    globals: true,
    environment: 'happy-dom',
    setupFiles: ['./src/test-setup.ts'],
    exclude: [...configDefaults.exclude],
  },
});
