import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { DEFAULT_MODULE } from '@/config/modules'

/**
 * 使用 hash 路由而非 history 路由。
 *
 * 原因：GitHub Pages 对未知路径一律返回 404，且实测在 actions/deploy-pages 部署方式下
 * 不会回落到 404.html（即便该文件确实存在并可访问）。若用 history 路由，
 * 用户刷新 `/-Agent/gasp` 会直接白屏。
 *
 * hash 路由下地址形如 `/-Agent/#/gasp`：路径部分恒定存在，刷新与直接访问都安全。
 * 代价是 URL 多一个 `#`——对内部工作台而言这个取舍是值得的。
 * 若将来部署到支持 SPA 回落的服务器（Nginx try_files），可换回 createWebHistory。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: `/${DEFAULT_MODULE}`,
  },
  {
    path: '/tast',
    name: 'tast',
    component: () => import('@/views/TastView.vue'),
    meta: { module: 'tast' },
  },
  {
    path: '/gasp',
    name: 'gasp',
    component: () => import('@/views/GaspView.vue'),
    meta: { module: 'gasp' },
  },
  {
    path: '/react',
    name: 'react',
    component: () => import('@/views/ReactView.vue'),
    meta: { module: 'react' },
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: `/${DEFAULT_MODULE}`,
  },
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
})
