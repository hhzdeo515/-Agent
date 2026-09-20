import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { DEFAULT_MODULE } from '@/config/modules'

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
  history: createWebHistory(),
  routes,
})
