<script setup lang="ts">
/**
 * 三栏工作台骨架。
 *
 * ┌──────────┬────────────────────────────┬──────────────────┐
 * │ 左侧导航  │ 模块头部（标识 + 状态条）    │ 右侧分栏          │
 * │ 三技能    ├────────────────────────────┤ 结果 / 反馈 / 进度 │
 * │ 切换      │ Agent 对话与推理过程         │                  │
 * │ 左下：    │                            │                  │
 * │ 个人信息  │                            │                  │
 * └──────────┴────────────────────────────┴──────────────────┘
 *
 * 右侧面板由各模块视图通过 <Teleport to="#wb-aside"> 注入：
 * 这样布局只有一份，而每个模块可以给出完全不同的右栏内容。
 */
import { computed } from 'vue'
import { MODULE_MAP, type ModuleKey } from '@/config/modules'
import SideNav from '@/components/SideNav.vue'
import ModuleHeader from '@/components/ModuleHeader.vue'

const props = defineProps<{ moduleKey: ModuleKey }>()

const meta = computed(() => MODULE_MAP[props.moduleKey])
</script>

<template>
  <!-- data-module 驱动整套点缀色：墨青 / 铜金 / 暗酒红 -->
  <div class="wb" :data-module="moduleKey">
    <SideNav :active="moduleKey" />

    <main class="wb__main">
      <ModuleHeader :meta="meta" />
      <div class="wb__center">
        <RouterView v-slot="{ Component }">
          <Transition name="gw-fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </div>
    </main>

    <!-- 右侧分栏：内容由当前模块 Teleport 注入 -->
    <aside id="wb-aside" class="wb__aside" />
  </div>
</template>

<style scoped>
.wb {
  display: grid;
  grid-template-columns: var(--gw-nav-w) minmax(0, 1fr) var(--gw-aside-w);
  height: 100vh;
  overflow: hidden;
  background: var(--gw-bg);
}

.wb__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  /* 中间主区域：大面积留白 */
  background: var(--gw-bg);
}

.wb__center {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--gw-s6) var(--gw-s7) var(--gw-s8);
}

.wb__aside {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  background: var(--gw-surface);
  /* 用细分割线而非阴影分隔，保持界面干净 */
  border-left: 1px solid var(--gw-line);
}

/* 窄屏：右栏收窄；更窄时隐藏右栏（内容仍可从中间区域进入） */
@media (max-width: 1440px) {
  .wb {
    grid-template-columns: var(--gw-nav-w) minmax(0, 1fr) 340px;
  }
}

@media (max-width: 1180px) {
  .wb {
    grid-template-columns: 68px minmax(0, 1fr);
  }
  .wb__aside {
    display: none;
  }
}
</style>
