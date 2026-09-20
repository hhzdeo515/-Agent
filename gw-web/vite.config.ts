import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * base 必须区分两种部署形态：
 *   · 本地开发 / 独立域名部署 → '/'
 *   · GitHub Pages 项目站点    → '/-Agent/'（仓库名子路径）
 * 若 Pages 上仍用 '/'，所有静态资源会解析到域名根目录而 404。
 */
const isPages = process.env.DEPLOY_TARGET === 'pages'

export default defineConfig({
  base: isPages ? '/-Agent/' : '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    // 后端已在 8080 运行；开发期直接代理，避免 CORS 与硬编码地址
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
})
