import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { DEFAULT_MODULE, MODULE_PATHS } from '@/config/modules'

/**
 * 四个模块的路由（与 AGENTS.md 的四模块划分一致）。
 *
 * 使用 hash 路由而非 history 路由：
 * GitHub Pages 对未知路径固定返回 404，且实测在 actions/deploy-pages 部署方式下
 * 不会回落到 404.html（该文件确实存在且可直接访问）。若用 history 路由，
 * 刷新 `/-Agent/feedback` 会白屏。
 * hash 路由下地址形如 `/-Agent/#/feedback`，路径部分恒定存在，刷新与直接访问都安全。
 * 若将来部署到支持 SPA 回落的服务器（Nginx try_files），换回 createWebHistory 即可。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: MODULE_PATHS[DEFAULT_MODULE] },
  {
    path: MODULE_PATHS.assistant,
    name: 'assistant',
    component: () => import('@/views/AssistantView.vue'),
    meta: { module: 'assistant' },
  },
  {
    path: MODULE_PATHS.intake,
    name: 'intake',
    component: () => import('@/views/IntakeView.vue'),
    meta: { module: 'intake' },
  },
  {
    path: MODULE_PATHS.feedback,
    name: 'feedback',
    component: () => import('@/views/FeedbackView.vue'),
    meta: { module: 'feedback' },
  },
  {
    path: MODULE_PATHS.finalReview,
    name: 'finalReview',
    component: () => import('@/views/FinalReviewView.vue'),
    meta: { module: 'finalReview' },
  },
  { path: '/:pathMatch(.*)*', redirect: MODULE_PATHS[DEFAULT_MODULE] },
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
})
