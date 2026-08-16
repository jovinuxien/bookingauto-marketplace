import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

/**
 * The SPA is built into the Spring Boot jar.
 *
 * Output goes to target/classes/static, which Spring Boot serves as the
 * application root -- so the built artefact is one jar, on one origin, with no
 * CORS and no second deployment to keep in step with the API it depends on.
 *
 * In development Vite serves the SPA and proxies /api to the running backend,
 * so the same relative URLs work in both modes and nothing has to know which
 * mode it is in.
 */
export default defineConfig({
  plugins: [react()],
  root: 'src/main/webapp',
  publicDir: false,
  resolve: {
    // Matches the `app/...` import style used throughout, so a file's imports
    // do not change when it moves between folders.
    alias: { app: path.resolve(__dirname, 'src/main/webapp/app') },
  },
  build: {
    outDir: path.resolve(__dirname, 'target/classes/static'),
    emptyOutDir: true,
    sourcemap: true,
    // Emitted so the server-rendered landing pages can reference the hashed
    // bundles. Without it the templates would have to hardcode filenames that
    // change on every build.
    manifest: true,
  },
  server: {
    port: 3002,
    proxy: {
      '/api': { target: process.env.BACKEND_URL ?? 'http://localhost:8090', changeOrigin: true },
    },
  },
});
