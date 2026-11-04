/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The API has no CORS configuration on purpose: the dashboard is served same-origin. In development Vite proxies
// /api to the local API (override with API_PROXY_TARGET); in containers nginx does the same (see Dockerfile).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: process.env.API_PROXY_TARGET ?? "http://localhost:8080", changeOrigin: false },
    },
  },
  build: { sourcemap: true, target: "es2022" },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./test/setup.ts"],
    css: false,
    restoreMocks: true,
  },
});
