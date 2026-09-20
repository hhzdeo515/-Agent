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
    /**
     * 忽略编辑器原子写入产生的临时文件。
     *
     * 编辑器（含部分 IDE 与文件工具的原子保存）会先写 `.<name>.<pid>.<rand>.tmpdir/` 再重命名，
     * Windows 上 Vite 的 FSWatcher 监视到这些临时目录时会抛 EBUSY，
     * 且该错误会作为未捕获异常**直接终止 dev server 进程**（表现为页面突然白屏）。
     */
    watch: {
      ignored: [
        '**/.*.tmpdir/**',
        '**/*.tmp',
        '**/*.tmpdir/**',
        '**/.git/**',
        '**/node_modules/**',
      ],
    },
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
