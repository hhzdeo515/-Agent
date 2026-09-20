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
  position: relative;
  display: grid;
  grid-template-columns: var(--gw-nav-w) minmax(0, 1fr) var(--gw-aside-w);
  height: 100vh;
  overflow: hidden;
  background: var(--gw-bg);
}

/* 环境光层：柔和漫射，暗示光源来自左上方。
   没有它，大面积留白会显得"死平"；有了它，留白才有空间感。 */
.wb::before {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--gw-ambient), var(--gw-ambient-accent);
  pointer-events: none;
  z-index: 0;
}

.wb > * {
  position: relative;
  z-index: 1;
}

.wb__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  /* 中间主区域：大面积留白，背景由环境光层提供 */
  background: transparent;
}

.wb__center {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--gw-s6) var(--gw-s8) var(--gw-s9);
}

.wb__aside {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  background: var(--gw-surface);
  /* 左栏用细线，右栏用极淡阴影——不同性质的分隔用不同手段，
     让右栏看起来是"浮在内容之上"而不是"被线切开" */
  border-left: 1px solid var(--gw-line);
  box-shadow: -1px 0 0 rgba(27, 46, 51, 0.015), -8px 0 24px rgba(27, 46, 51, 0.02);
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
