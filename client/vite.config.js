import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '')
  const backend = env.VITE_DEV_PROXY_TARGET || 'http://localhost:9090'
  return {
    envDir: '..',
    // GitHub Pages 部署在 /SceneMind/ 子路径下；本地开发保持根路径。
    base: env.VITE_BASE || process.env.VITE_BASE || '/',
    plugins: [vue()],
    server: {
      proxy: Object.fromEntries(
        ['/user', '/media', '/analysis', '/admin', '/health'].map(path => [
          path,
          { target: backend, changeOrigin: true }
        ])
      )
    }
  }
})
