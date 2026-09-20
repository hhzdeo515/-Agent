<script setup lang="ts">
/**
 * 右栏面板卡片：统一的标题栏 + 细分隔线 + 内容区。
 *
 * 三个模块的右栏内容差异很大，但都复用这一个壳，
 * 保证「模块可区分、整体视觉统一」——差异只体现在点缀色与内容，不体现在结构。
 *
 * 视觉细节：标题栏用极淡的点缀色底 + 一个 2px 的点缀色标识条，
 * 使右栏在滚动时也能快速定位分区，而不需要加粗边框或大标题。
 */
defineProps<{
  title: string
  /** 标题右侧的补充说明或计数 */
  hint?: string
  /** 去掉内边距，用于放列表或时间线 */
  flush?: boolean
}>()
</script>

<template>
  <section class="pc">
    <header class="pc__head">
      <span class="pc__mark" aria-hidden="true" />
      <h2 class="pc__title">{{ title }}</h2>
      <span v-if="hint" class="pc__hint">{{ hint }}</span>
      <slot name="head-extra" />
    </header>
    <div class="pc__body" :class="{ 'is-flush': flush }">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.pc {
  position: relative;
  border-bottom: 1px solid var(--gw-line-soft);
}

.pc:last-child {
  border-bottom: 0;
}

.pc__head {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: var(--gw-s4) var(--gw-s5) var(--gw-s3);
  /* 半透明底 + 模糊：滚动时内容从标题下滑过而不打架 */
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(6px);
}

/* 点缀色标识条：2px 宽，短，克制但足以定位 */
.pc__mark {
  width: 2px;
  height: 12px;
  flex: none;
  border-radius: var(--gw-r-pill);
  background: var(--gw-accent);
  opacity: 0.85;
}

.pc__title {
  font-size: var(--gw-fs-sm);
  font-weight: 600;
  color: var(--gw-text);
  letter-spacing: 0.01em;
  flex: none;
}

.pc__hint {
  font-size: var(--gw-fs-xs);
  color: var(--gw-text-tertiary);
  font-variant-numeric: tabular-nums;
  margin-left: auto;
  flex: none;
  padding: 1px 6px;
  border-radius: var(--gw-r-pill);
  background: var(--gw-bg-sunken);
}

.pc__body {
  padding: 0 var(--gw-s5) var(--gw-s5);
}

.pc__body.is-flush {
  padding: 0 0 var(--gw-s2);
}
</style>
