/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const proxyTarget = env.VITE_PROXY_TARGET
  const waybillTarget = env.VITE_WAYBILL_URL || 'http://localhost:8081'
  const paymentTarget = env.VITE_PAYMENT_URL || 'http://localhost:8082'
  const configTarget = env.VITE_CONFIG_URL || 'http://localhost:8888'

  return {
    plugins: [
      react(),
    ],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    server: {
      port: 3000,
      proxy: {
        ...(proxyTarget
          ? {
              '/api': {
                target: proxyTarget,
                changeOrigin: true,
              },
            }
          : {
              '/api/waybills': {
                target: waybillTarget,
                changeOrigin: true,
              },
              '/api/payments': {
                target: paymentTarget,
                changeOrigin: true,
              },
              // BOR-74/79 audit control and the BOR-89 audit layer both live in
              // the payments service.
              '/api/audit-control': {
                target: paymentTarget,
                changeOrigin: true,
              },
              '/api/config': {
                target: configTarget,
                changeOrigin: true,
              },
            }),
      },
    },
    // Vitest reads this file directly (BOR-81/82: the frontend had no test
    // runner at all; the audit page's data hooks now carry regression tests).
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      css: false,
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html'],
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/types/**'],
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      rollupOptions: {
        output: {
          manualChunks: {
            'react-vendor': ['react', 'react-dom'],
            'tanstack': ['@tanstack/react-router', '@tanstack/react-query', '@tanstack/react-table'],
            'radix': [
              '@radix-ui/react-dialog',
              '@radix-ui/react-dropdown-menu',
              '@radix-ui/react-select',
              '@radix-ui/react-toast',
            ],
            'excel': ['xlsx'],
          },
        },
      },
    },
  }
})
