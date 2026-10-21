/// <reference types="vitest/config" />
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// Phase 12: vite-plugin-pwa for installability / caching of shell assets.
// Do NOT enable offline financial operations — money movement must stay online.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const enablePwa = env.VITE_ENABLE_PWA === 'true'

  return {
    plugins: [
      react(),
      VitePWA({
        // Always configure the plugin so production builds can emit a SW when enabled.
        // When disabled, selfDestroying unregisters any previously installed worker.
        selfDestroying: !enablePwa,
        registerType: 'prompt',
        injectRegister: false,
        includeAssets: [
          'favicon.svg',
          'offline.html',
          'branding/ouwealth-community-logo.png',
          'branding/ouwealth-community-logo-on-dark.png',
          'icons/icon.svg',
          'icons/icon-192.png',
          'icons/icon-512.png',
          'icons/apple-touch-icon.png',
        ],
        manifest: {
          name: 'OuWealth Community',
          short_name: 'OuWealth',
          description: 'Accumulate your wealth in an instant',
          theme_color: '#1B4D8C',
          background_color: '#FFFFFF',
          display: 'standalone',
          start_url: '/',
          scope: '/',
          icons: [
            {
              src: 'icons/icon-192.png',
              sizes: '192x192',
              type: 'image/png',
              purpose: 'any',
            },
            {
              src: 'icons/icon-512.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'any',
            },
            {
              src: 'icons/icon-512.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'maskable',
            },
          ],
        },
        workbox: {
          // SPA shell: index.html for client routes. Dedicated offline.html is precached in public/.
          navigateFallback: 'index.html',
          globPatterns: ['**/*.{js,css,html,ico,png,jpg,jpeg,svg,woff2,webmanifest}'],
          runtimeCaching: [
            {
              urlPattern: /^https:\/\/fonts\.googleapis\.com\/.*/i,
              handler: 'CacheFirst',
              options: {
                cacheName: 'google-fonts-cache',
                expiration: { maxEntries: 10, maxAgeSeconds: 60 * 60 * 24 * 365 },
              },
            },
            {
              urlPattern: /^https:\/\/fonts\.gstatic\.com\/.*/i,
              handler: 'CacheFirst',
              options: {
                cacheName: 'gstatic-fonts-cache',
                expiration: { maxEntries: 10, maxAgeSeconds: 60 * 60 * 24 * 365 },
              },
            },
          ],
          // Never cache financial / API responses for offline mutation.
          navigateFallbackDenylist: [/^\/api\//],
        },
        devOptions: {
          enabled: false,
        },
      }),
    ],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
      css: true,
    },
  }
})
