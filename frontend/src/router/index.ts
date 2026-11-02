/** 路由：/ → /wires；左侧菜单两个页面 —— Wire Graph 与 Drivers and Assets */
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/wires' },
    {
      path: '/wires',
      name: 'wires',
      component: () => import('@/views/WiresPage.vue'),
    },
    {
      path: '/drivers-assets',
      name: 'drivers-assets',
      component: () => import('@/views/DriversAssetsPage.vue'),
    },
  ],
})

export default router
