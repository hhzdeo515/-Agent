<script setup lang="ts">
/**
 * 右栏面板卡片：统一的标题栏 + 细分割线 + 内容区。
 *
 * 三个模块的右栏内容差异很大，但都复用这一个壳，
 * 保证「模块可区分、整体视觉统一」——差异只体现在点缀色与内容，不体现在结构。
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
  border-bottom: 1px solid var(--gw-line);
}

.pc:last-child {
  border-bottom: 0;
}

.pc__head {
  display: flex;
  align-items: center;
  gap: var(--gw-s3);
  padding: var(--gw-s4) var(--gw-s5) var(--gw-s3);
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
}

.pc__body {
  padding: 0 var(--gw-s5) var(--gw-s5);
}

.pc__body.is-flush {
  padding: 0 0 var(--gw-s2);
}
</style>
